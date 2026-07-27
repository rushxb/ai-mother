package com.rush.rushaicodemother.infrastructure.git;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 拥有一次提交事务使用的短期 Git 索引、路径规范和挂钩隔离资源。
 *
 * <p>所有资源都是经过验证的 Git 元数据目录的直接子级。路径规范已传递
 * 通过NUL分隔的文件这样大的生成不会超出Windows命令行限制。</p>
 */
@Slf4j
@Component
public class GitTransactionResourceManager {

    private static final String RESOURCE_PREFIX = "ai-code-mother-";
    private static final String INDEX_SUFFIX = ".index";
    private static final String PATHSPEC_SUFFIX = ".pathspec";
    private static final String STAGED_OUTPUT_SUFFIX = ".staged";
    private static final String HOOKS_SUFFIX = ".hooks";
    private static final Set<java.nio.file.OpenOption> READ_NOFOLLOW_OPTIONS = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
    );

    public GitTransactionResources create(Path gitDirectory,
                                          List<String> pathspecs,
                                          int maxPathspecBytes) throws GitTransactionResourceException {
        Path safeGitDirectory = requireSafeGitDirectory(gitDirectory);
        byte[] encodedPathspecs = encodePathspecs(pathspecs, maxPathspecBytes);
        String transactionId = UUID.randomUUID().toString();
        Path temporaryIndex = resolveDirectChild(
                safeGitDirectory,
                RESOURCE_PREFIX + transactionId + INDEX_SUFFIX
        );
        Path temporaryPathspec = resolveDirectChild(
                safeGitDirectory,
                RESOURCE_PREFIX + transactionId + PATHSPEC_SUFFIX
        );
        Path temporaryStagedOutput = resolveDirectChild(
                safeGitDirectory,
                RESOURCE_PREFIX + transactionId + STAGED_OUTPUT_SUFFIX
        );
        Path temporaryHooksDirectory = resolveDirectChild(
                safeGitDirectory,
                RESOURCE_PREFIX + transactionId + HOOKS_SUFFIX
        );

        try {
            Files.createDirectory(temporaryHooksDirectory);
            Files.write(
                    temporaryPathspec,
                    encodedPathspecs,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS
            );
            return new GitTransactionResources(
                    safeGitDirectory,
                    temporaryIndex,
                    temporaryPathspec,
                    temporaryStagedOutput,
                    temporaryHooksDirectory
            );
        } catch (IOException | RuntimeException exception) {
            deleteIfSafe(safeGitDirectory, temporaryPathspec, ResourceType.PATHSPEC);
            deleteIfSafe(safeGitDirectory, temporaryHooksDirectory, ResourceType.HOOKS_DIRECTORY);
            throw new GitTransactionResourceException(
                    GitTransactionResourceException.Reason.RESOURCE_CREATION_FAILED,
                    "无法创建 Git 提交事务资源",
                    exception
            );
        }
    }

    public void cleanup(GitTransactionResources resources) {
        if (resources == null) {
            return;
        }
        deleteIfSafe(resources.gitDirectory(), resources.temporaryIndexLock(), ResourceType.INDEX_LOCK);
        deleteIfSafe(resources.gitDirectory(), resources.temporaryIndex(), ResourceType.INDEX);
        deleteIfSafe(resources.gitDirectory(), resources.temporaryPathspec(), ResourceType.PATHSPEC);
        deleteIfSafe(resources.gitDirectory(), resources.temporaryStagedOutput(), ResourceType.STAGED_OUTPUT);
        deleteIfSafe(resources.gitDirectory(), resources.temporaryHooksDirectory(), ResourceType.HOOKS_DIRECTORY);
    }

    /** 读取 {@code git diff --name-only -z} 生成的 NUL 分隔的暂存文件输出。 */
    public List<String> readStagedFiles(GitTransactionResources resources,
                                        int maxOutputBytes) throws GitTransactionResourceException {
        Objects.requireNonNull(resources, "resources must not be null");
        Path outputPath = resources.temporaryStagedOutput();
        if (!isOwnedResource(resources.gitDirectory(), outputPath, ResourceType.STAGED_OUTPUT)
                || maxOutputBytes <= 0) {
            throw invalidStagedOutput();
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    outputPath,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()
                    || !attributes.isRegularFile()
                    || attributes.size() > maxOutputBytes) {
                throw invalidStagedOutput();
            }
            byte[] bytes = readBounded(outputPath, maxOutputBytes);
            return decodeNulDelimitedPaths(bytes);
        } catch (GitTransactionResourceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new GitTransactionResourceException(
                    GitTransactionResourceException.Reason.STAGED_OUTPUT_INVALID,
                    "Git 暂存文件输出不可用",
                    exception
            );
        }
    }

    private Path requireSafeGitDirectory(Path gitDirectory) throws GitTransactionResourceException {
        if (gitDirectory == null) {
            throw invalidRoot();
        }
        Path normalized = gitDirectory.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    normalized,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()
                    || !attributes.isDirectory()
                    || Files.isSymbolicLink(normalized)) {
                throw invalidRoot();
            }
            return normalized;
        } catch (GitTransactionResourceException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new GitTransactionResourceException(
                    GitTransactionResourceException.Reason.INVALID_GIT_DIRECTORY,
                    "Git 元数据目录不可用",
                    exception
            );
        }
    }

    private byte[] encodePathspecs(List<String> pathspecs,
                                   int maxPathspecBytes) throws GitTransactionResourceException {
        if (pathspecs == null || pathspecs.isEmpty() || maxPathspecBytes <= 0) {
            throw new GitTransactionResourceException(
                    GitTransactionResourceException.Reason.INVALID_PATHSPEC,
                    "Git 路径清单不能为空"
            );
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxPathspecBytes, 16 * 1024));
        for (String pathspec : pathspecs) {
            if (pathspec == null || pathspec.isBlank() || pathspec.indexOf('\0') >= 0) {
                throw new GitTransactionResourceException(
                        GitTransactionResourceException.Reason.INVALID_PATHSPEC,
                        "Git 路径清单包含非法路径"
                );
            }
            byte[] encoded = pathspec.getBytes(StandardCharsets.UTF_8);
            if ((long) output.size() + encoded.length + 1L > maxPathspecBytes) {
                throw new GitTransactionResourceException(
                        GitTransactionResourceException.Reason.PATHSPEC_LIMIT_EXCEEDED,
                        "Git 路径清单超过字节上限"
                );
            }
            output.writeBytes(encoded);
            output.write(0);
        }
        return output.toByteArray();
    }

    private byte[] readBounded(Path path, int maxBytes) throws IOException, GitTransactionResourceException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maxBytes, 16 * 1024));
        try (SeekableByteChannel channel = Files.newByteChannel(path, READ_NOFOLLOW_OPTIONS)) {
            ByteBuffer buffer = ByteBuffer.allocate(16 * 1024);
            while (true) {
                int bytesRead = channel.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                if ((long) output.size() + bytesRead > maxBytes) {
                    throw invalidStagedOutput();
                }
                buffer.flip();
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                output.writeBytes(chunk);
                buffer.clear();
            }
        }
        return output.toByteArray();
    }

    private List<String> decodeNulDelimitedPaths(byte[] bytes) throws GitTransactionResourceException {
        if (bytes.length == 0) {
            return List.of();
        }
        if (bytes[bytes.length - 1] != 0) {
            throw invalidStagedOutput();
        }
        List<String> paths = new java.util.ArrayList<>();
        int start = 0;
        for (int index = 0; index < bytes.length; index++) {
            if (bytes[index] != 0) {
                continue;
            }
            String path = decodeUtf8Path(bytes, start, index - start);
            if (path.isBlank() || path.indexOf('\0') >= 0) {
                throw invalidStagedOutput();
            }
            paths.add(path.replace('\\', '/'));
            start = index + 1;
        }
        return List.copyOf(paths);
    }

    private String decodeUtf8Path(byte[] bytes,
                                  int offset,
                                  int length) throws GitTransactionResourceException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw invalidStagedOutput();
        }
    }

    private Path resolveDirectChild(Path parent, String fileName) throws GitTransactionResourceException {
        Path child = parent.resolve(fileName).normalize();
        if (child.getParent() == null || !child.getParent().equals(parent)) {
            throw new GitTransactionResourceException(
                    GitTransactionResourceException.Reason.INVALID_GIT_DIRECTORY,
                    "Git 临时资源路径越界"
            );
        }
        return child;
    }

    private void deleteIfSafe(Path expectedParent, Path path, ResourceType resourceType) {
        if (!isOwnedResource(expectedParent, path, resourceType)) {
            log.warn("拒绝清理不受信任的 Git 临时资源，resourceType: {}", resourceType);
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException exception) {
            log.warn(
                    "清理 Git 临时资源失败，resourceType: {}, exceptionType: {}",
                    resourceType,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private boolean isOwnedResource(Path expectedParent, Path path, ResourceType resourceType) {
        if (expectedParent == null || path == null || path.getFileName() == null || path.getParent() == null) {
            return false;
        }
        Path normalizedParent = expectedParent.toAbsolutePath().normalize();
        Path normalizedPath = path.toAbsolutePath().normalize();
        if (!normalizedParent.equals(normalizedPath.getParent())) {
            return false;
        }
        String fileName = normalizedPath.getFileName().toString();
        return fileName.startsWith(RESOURCE_PREFIX) && fileName.endsWith(resourceType.suffix);
    }

    private GitTransactionResourceException invalidRoot() {
        return new GitTransactionResourceException(
                GitTransactionResourceException.Reason.INVALID_GIT_DIRECTORY,
                "Git 元数据目录无效或为符号链接"
        );
    }

    private GitTransactionResourceException invalidStagedOutput() {
        return new GitTransactionResourceException(
                GitTransactionResourceException.Reason.STAGED_OUTPUT_INVALID,
                "Git 暂存文件输出无效"
        );
    }

    private enum ResourceType {
        INDEX(INDEX_SUFFIX),
        INDEX_LOCK(INDEX_SUFFIX + ".lock"),
        PATHSPEC(PATHSPEC_SUFFIX),
        STAGED_OUTPUT(STAGED_OUTPUT_SUFFIX),
        HOOKS_DIRECTORY(HOOKS_SUFFIX);

        private final String suffix;

        ResourceType(String suffix) {
            this.suffix = suffix;
        }
    }

    public record GitTransactionResources(
            Path gitDirectory,
            Path temporaryIndex,
            Path temporaryPathspec,
            Path temporaryStagedOutput,
            Path temporaryHooksDirectory
    ) {

        public GitTransactionResources {
            Objects.requireNonNull(gitDirectory, "gitDirectory must not be null");
            Objects.requireNonNull(temporaryIndex, "temporaryIndex must not be null");
            Objects.requireNonNull(temporaryPathspec, "temporaryPathspec must not be null");
            Objects.requireNonNull(temporaryStagedOutput, "temporaryStagedOutput must not be null");
            Objects.requireNonNull(temporaryHooksDirectory, "temporaryHooksDirectory must not be null");
        }

        public Path temporaryIndexLock() {
            return temporaryIndex.resolveSibling(temporaryIndex.getFileName() + ".lock");
        }
    }

    public static final class GitTransactionResourceException extends IOException {

        private final Reason reason;

        public GitTransactionResourceException(Reason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public GitTransactionResourceException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = Objects.requireNonNull(reason, "reason must not be null");
        }

        public Reason reason() {
            return reason;
        }

        public enum Reason {
            INVALID_GIT_DIRECTORY,
            INVALID_PATHSPEC,
            PATHSPEC_LIMIT_EXCEEDED,
            STAGED_OUTPUT_INVALID,
            RESOURCE_CREATION_FAILED
        }
    }
}
