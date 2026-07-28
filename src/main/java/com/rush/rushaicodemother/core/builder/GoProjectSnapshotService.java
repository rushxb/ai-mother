package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 为 Go 构建门禁计算有界、不可绕过的源码内容指纹。
 *
 * <p>除版本库元数据外，项目内所有普通文件都会参与指纹，以覆盖 go:embed 引用的任意资源。</p>
 */
@Component
public class GoProjectSnapshotService {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final Runnable NO_OP_CONTINUATION_CHECK = () -> {
    };
    private static final Set<OpenOption> READ_NOFOLLOW_OPTIONS = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
    );

    private final WorkspaceFileSystemProperties properties;

    public GoProjectSnapshotService(WorkspaceFileSystemProperties properties) {
        this.properties = Objects.requireNonNull(properties, "工作区文件系统配置不能为空");
    }

    /** 返回{@code capture}。 */
    GoProjectSnapshot capture(Path projectRoot) throws IOException {
        return capture(projectRoot, NO_OP_CONTINUATION_CHECK);
    }

    /** 在调用方取消边界内采集 Go 项目快照。 */
    GoProjectSnapshot capture(Path projectRoot, Runnable continuationCheck) throws IOException {
        Runnable effectiveCheck = continuationCheck == null
                ? NO_OP_CONTINUATION_CHECK
                : continuationCheck;
        effectiveCheck.run();
        Path normalizedRoot = requireProjectRoot(projectRoot);
        List<ProjectFile> files = collectFiles(normalizedRoot, effectiveCheck);
        files.sort(Comparator.comparing(ProjectFile::relativePath));

        MessageDigest digest = newSha256Digest();
        for (ProjectFile file : files) {
            effectiveCheck.run();
            updateLengthPrefixed(digest, file.relativePath().getBytes(StandardCharsets.UTF_8));
            updateLong(digest, file.size());
            appendFileContent(digest, file, effectiveCheck);
        }
        effectiveCheck.run();
        return new GoProjectSnapshot(HexFormat.of().formatHex(digest.digest()));
    }

    /** 校验并返回有效的项目根。 */
    private Path requireProjectRoot(Path projectRoot) throws IOException {
        if (projectRoot == null) {
            throw new IOException("Go 项目根目录不能为空");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(normalizedRoot)) {
            throw new IOException("Go 项目根目录无效或为符号链接");
        }
        return normalizedRoot;
    }

    /** 采集并汇总文件。 */
    private List<ProjectFile> collectFiles(Path projectRoot, Runnable continuationCheck) throws IOException {
        List<ProjectFile> files = new ArrayList<>();
        AtomicInteger fileCount = new AtomicInteger();
        AtomicLong totalBytes = new AtomicLong();
        Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
            /**
 * 在访问目录内容前执行安全校验和资源边界判断。
 *
 * @param directory 目录
 * @param attributes 属性
 * @return 方法执行结果
 */
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                continuationCheck.run();
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                    throw new IOException("Go 项目包含不允许参与缓存的符号链接目录: "
                            + normalizePath(projectRoot.relativize(directory)));
                }
                int depth = directory.equals(projectRoot)
                        ? 0
                        : projectRoot.relativize(directory).getNameCount();
                if (depth > properties.getMaxDirectoryDepth()) {
                    throw new IOException("Go 项目目录深度超过快照上限: "
                            + properties.getMaxDirectoryDepth());
                }
                if (!directory.equals(projectRoot) && isVersionControlDirectory(directory)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            /**
 * 返回访问文件。
 *
 * @param file 文件
 * @param attributes 属性
 * @return {@code Go}项目快照
 */
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                continuationCheck.run();
                String relativePath = normalizePath(projectRoot.relativize(file));
                if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
                    throw new IOException("Go 项目包含不允许参与缓存的符号链接文件: " + relativePath);
                }
                if (!attributes.isRegularFile()) {
                    return FileVisitResult.CONTINUE;
                }
                if (fileCount.incrementAndGet() > properties.getMaxFiles()) {
                    throw new IOException("Go 项目文件数量超过快照上限: " + properties.getMaxFiles());
                }
                if (attributes.size() > properties.getMaxFileBytes()) {
                    throw new IOException("Go 项目文件超过快照大小上限: " + relativePath);
                }
                long scannedBytes = addBytes(totalBytes, attributes.size());
                if (scannedBytes > properties.getMaxScannedBytes()) {
                    throw new IOException("Go 项目总字节数超过快照上限: "
                            + properties.getMaxScannedBytes());
                }
                files.add(new ProjectFile(
                        file,
                        relativePath,
                        attributes.size(),
                        attributes.lastModifiedTime().toMillis(),
                        attributes.fileKey()
                ));
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    /** 返回{@code add}对应的字节数。 */
    private long addBytes(AtomicLong totalBytes, long size) throws IOException {
        try {
            return totalBytes.updateAndGet(current -> Math.addExact(current, size));
        } catch (ArithmeticException exception) {
            throw new IOException("Go 项目总字节数超过快照上限", exception);
        }
    }

    /** 追加文件内容。 */
    private void appendFileContent(
            MessageDigest digest,
            ProjectFile projectFile,
            Runnable continuationCheck
    ) throws IOException {
        continuationCheck.run();
        BasicFileAttributes before = readRegularFileAttributes(projectFile.path());
        if (!projectFile.matches(before)) {
            throw new IOException("Go 项目文件在快照读取前发生变化: " + projectFile.relativePath());
        }

        long bytesRead = 0L;
        ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        try (SeekableByteChannel channel = Files.newByteChannel(projectFile.path(), READ_NOFOLLOW_OPTIONS)) {
            while (true) {
                continuationCheck.run();
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                bytesRead = addExact(bytesRead, read, projectFile.relativePath());
                if (bytesRead > properties.getMaxFileBytes()) {
                    throw new IOException("Go 项目文件超过快照大小上限: " + projectFile.relativePath());
                }
                buffer.flip();
                digest.update(buffer);
                buffer.clear();
            }
        }

        continuationCheck.run();
        BasicFileAttributes after = readRegularFileAttributes(projectFile.path());
        if (bytesRead != projectFile.size() || !projectFile.matches(after)) {
            throw new IOException("Go 项目文件在快照读取期间发生变化: " + projectFile.relativePath());
        }
    }

    private BasicFileAttributes readRegularFileAttributes(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                file,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        if (attributes.isSymbolicLink() || !attributes.isRegularFile() || Files.isSymbolicLink(file)) {
            throw new IOException("Go 项目快照目标不是安全的普通文件");
        }
        return attributes;
    }

    /** 添加{@code Exact}。 */
    private long addExact(long current, long increment, String relativePath) throws IOException {
        try {
            return Math.addExact(current, increment);
        } catch (ArithmeticException exception) {
            throw new IOException("Go 项目文件超过快照大小上限: " + relativePath, exception);
        }
    }

    private void updateLengthPrefixed(MessageDigest digest, byte[] value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
        digest.update(value);
    }

    private void updateLong(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    /** 返回{@code new}{@code Sha256}摘要。 */
    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    private boolean isVersionControlDirectory(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null && ".git".equals(fileName.toString().toLowerCase(Locale.ROOT));
    }

    private String normalizePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }

    private record ProjectFile(
            Path path,
            String relativePath,
            long size,
            long lastModifiedTime,
            Object fileKey
    ) {
        private boolean matches(BasicFileAttributes attributes) {
            if (size != attributes.size()
                    || lastModifiedTime != attributes.lastModifiedTime().toMillis()) {
                return false;
            }
            return fileKey == null || attributes.fileKey() == null
                    || fileKey.equals(attributes.fileKey());
        }
    }
}
