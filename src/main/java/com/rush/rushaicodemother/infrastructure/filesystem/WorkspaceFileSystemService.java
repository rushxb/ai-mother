package com.rush.rushaicodemother.infrastructure.filesystem;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 用于工作区扫描、有限读取和事务目录复制的深层文件系统模块。
 *
 * <p>实现不会跟随符号链接。每次读取都依据扫描选中文件时捕获的元数据进行复核；
 * 快照副本完成暂存后才会对外可见。</p>
 */
@Component
public class WorkspaceFileSystemService {

    private static final int BUFFER_SIZE = 16 * 1024;
    private static final int MUTATION_LOCK_STRIPES = 64;
    private static final String INTERACTIVE_TEMP_FILE_PREFIX = ".app-code-";
    private static final Set<String> IGNORED_DIRECTORY_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage",
            ".ai-code-index", ".cache", ".turbo", ".next"
    );
    private static final Set<String> SENSITIVE_SCAN_FILE_NAMES = Set.of(
            ".env", ".env.local", ".env.development", ".env.production", ".env.test"
    );
    private static final Set<OpenOption> READ_NOFOLLOW_OPTIONS = Set.of(
            StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS
    );
    private static final Set<OpenOption> CREATE_NOFOLLOW_OPTIONS = Set.of(
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS
    );
    private static final Set<OpenOption> REPLACE_NOFOLLOW_OPTIONS = Set.of(
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS
    );

    private final WorkspaceFileSystemProperties properties;
    private final ReentrantLock[] mutationLocks;
    private final MoveOperation moveOperation;

    @Autowired
    public WorkspaceFileSystemService(WorkspaceFileSystemProperties properties) {
        this(properties, Files::move);
    }

    WorkspaceFileSystemService(WorkspaceFileSystemProperties properties, MoveOperation moveOperation) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.mutationLocks = createMutationLocks();
        this.moveOperation = Objects.requireNonNull(moveOperation, "moveOperation must not be null");
    }

    /** 扫描一次项目并为下游消费者返回稳定的相对路径元数据。 */
    public WorkspaceScan scanProject(Path rootDirectory) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        ScanCollector collector = new ScanCollector(root);
        Files.walkFileTree(root, collector);
        collector.files.sort(Comparator.comparing(WorkspaceFileMetadata::relativePath));
        return new WorkspaceScan(root, collector.files, collector.totalBytes);
    }

    /** 列出有界交互式树，同时保留可见的空目录。 */
    public List<WorkspaceTreeNode> listTree(Path rootDirectory,
                                            int requestedMaxDepth,
                                            WorkspaceTreeFilter filter) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        if (requestedMaxDepth <= 0) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH,
                    "工作区文件树深度上限必须大于 0");
        }
        int effectiveMaxDepth = Math.min(
                requestedMaxDepth,
                Math.min(properties.getMaxInteractiveTreeDepth(), properties.getMaxDirectoryDepth())
        );
        TreeCollector collector = new TreeCollector(
                root,
                effectiveMaxDepth,
                Objects.requireNonNull(filter, "filter must not be null")
        );
        return collector.listDirectory(root, 1);
    }

    /** 解析一个现有的常规文件并捕获以后安全读取或写入所需的身份。 */
    public WorkspaceFileMetadata resolveExistingFile(Path rootDirectory, String relativePath) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path absolutePath = resolveRelativeFile(root, relativePath);
        BasicFileAttributes attributes = readRegularFileAttributes(absolutePath);
        return metadata(root, absolutePath, attributes);
    }

    /** 在调用者提供的资源限制下读取显式解析的 UTF-8 文件。 */
    public String readUtf8(Path rootDirectory, WorkspaceFileMetadata file, long requestedMaxBytes)
            throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        return readUtf8File(rootDirectory, file, effectiveFileLimit(requestedMaxBytes));
    }

    /**
     * 仅当现有 UTF-8 文件仍具有预期标识时才替换它。
     *
     * <p>进程内的写入按路径串行执行；替换前强制刷入持久存储，并在底层文件系统
     * 支持时使用原子移动。</p>
     */
    public WorkspaceFileMetadata replaceUtf8Atomically(Path rootDirectory,
                                                        WorkspaceFileMetadata expectedFile,
                                                        String content,
                                                        long requestedMaxBytes) throws IOException {
        Objects.requireNonNull(expectedFile, "expectedFile must not be null");
        Objects.requireNonNull(content, "content must not be null");
        long effectiveLimit = effectiveFileLimit(requestedMaxBytes);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > effectiveLimit) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE,
                    "工作区文件超过交互式写入上限");
        }

        Path root = requireExistingDirectory(rootDirectory);
        Path target = resolveRelativeFile(root, expectedFile.relativePath());
        ReentrantLock lock = mutationLockFor(target);
        lock.lock();
        try {
            requireMatchingFile(target, expectedFile);
            Path temporary = interactiveTemporarySibling(target);
            try {
                Files.copy(
                        target,
                        temporary,
                        StandardCopyOption.COPY_ATTRIBUTES,
                        LinkOption.NOFOLLOW_LINKS
                );
                try (FileChannel channel = FileChannel.open(temporary, REPLACE_NOFOLLOW_OPTIONS)) {
                    writeFully(channel, ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
                requireMatchingFile(target, expectedFile);
                moveReplacing(temporary, target);
                return metadata(root, target, readRegularFileAttributes(target));
            } catch (WorkspaceFileSystemException exception) {
                throw exception;
            } catch (IOException exception) {
                throw failure(WorkspaceFileSystemException.Reason.REPLACE_FAILED,
                        "原子替换工作区文件失败", exception);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 删除一个现有的常规文件而不遵循符号链接。 */
    public boolean deleteFileIfExists(Path rootDirectory, String relativePath) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path target = resolveRelativeFile(root, relativePath);
        ReentrantLock lock = mutationLockFor(target);
        lock.lock();
        try {
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    target,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink()) {
                throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                        "工作区文件不能是符号链接");
            }
            if (!attributes.isRegularFile()) {
                throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH,
                        "工作区目标不是普通文件");
            }
            return Files.deleteIfExists(target);
        } finally {
            lock.unlock();
        }
    }

    /** 将扫描的文件读取为 UTF-8，不跟随符号链接，也不接受标识已变化的文件。 */
    public String readUtf8(WorkspaceScan scan, WorkspaceFileMetadata file, long requestedMaxBytes) throws IOException {
        Objects.requireNonNull(scan, "scan must not be null");
        Objects.requireNonNull(file, "file must not be null");
        return readUtf8File(scan.root(), file, effectiveReadLimit(requestedMaxBytes));
    }

    private String readUtf8File(Path rootDirectory, WorkspaceFileMetadata file, long effectiveLimit) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path absolutePath = resolveRelativeFile(root, file.relativePath());
        BasicFileAttributes before = requireMatchingFile(absolutePath, file);
        if (before.size() > effectiveLimit) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, "工作区文件超过读取上限");
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(before.size(), BUFFER_SIZE * 4L));
        try (SeekableByteChannel channel = Files.newByteChannel(absolutePath, READ_NOFOLLOW_OPTIONS)) {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            long totalRead = 0L;
            while (true) {
                int bytesRead = channel.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }
                buffer.flip();
                int readable = buffer.remaining();
                totalRead = safeAdd(totalRead, readable, WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED,
                        "工作区文件读取超过资源上限");
                if (totalRead > effectiveLimit) {
                    throw failure(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, "工作区文件超过读取上限");
                }
                output.write(buffer.array(), buffer.position(), readable);
                buffer.clear();
            }
        }
        BasicFileAttributes after = readRegularFileAttributes(absolutePath);
        if (!sameIdentity(before, after) || output.size() != after.size()) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED, "工作区文件在读取期间发生变化");
        }
        return output.toString(StandardCharsets.UTF_8);
    }

    /** 读取工作区根目录下可选生成的 UTF-8 文件。 */
    public Optional<String> readOptionalUtf8(Path rootDirectory, String relativePath, long requestedMaxBytes)
            throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path absolutePath = resolveRelativePath(root, relativePath);
        if (!Files.exists(absolutePath, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        BasicFileAttributes attributes = readRegularFileAttributes(absolutePath);
        WorkspaceFileMetadata metadata = metadata(root, absolutePath, attributes);
        long effectiveLimit = Math.min(requestedMaxBytes, properties.getMaxPersistedFileBytes());
        if (effectiveLimit <= 0) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区读取上限必须大于 0");
        }
        return Optional.of(readUtf8File(root, metadata, effectiveLimit));
    }

    /** 通过同级临时文件和原子替换（如果支持）写入生成的 UTF-8 文件。 */
    public void writeUtf8Atomically(Path rootDirectory, String relativePath, String content) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        byte[] bytes = Objects.requireNonNullElse(content, "").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.getMaxPersistedFileBytes()) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, "工作区元数据文件超过写入上限");
        }
        Path target = resolveRelativePath(root, relativePath);
        Path parent = target.getParent();
        if (parent == null) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区文件路径无效");
        }
        createDirectoriesWithinRoot(root, parent);
        ReentrantLock lock = mutationLockFor(target);
        lock.lock();
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
                throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                        "工作区文件不能是符号链接");
            }
            Path temporary = parent.resolve("." + target.getFileName() + ".tmp-" + UUID.randomUUID());
            try {
                try (FileChannel channel = FileChannel.open(temporary, CREATE_NOFOLLOW_OPTIONS)) {
                    writeFully(channel, ByteBuffer.wrap(bytes));
                    channel.force(true);
                }
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(target)) {
                    throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                            "工作区文件不能是符号链接");
                }
                moveReplacing(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } finally {
            lock.unlock();
        }
    }

    /** 比较两个扫描的文件，而不将任一完整文件加载到内存中。 */
    public boolean contentEquals(WorkspaceScan leftScan,
                                 WorkspaceFileMetadata left,
                                 WorkspaceScan rightScan,
                                 WorkspaceFileMetadata right) throws IOException {
        if (left.size() != right.size()) {
            return false;
        }
        Path leftPath = resolveRelativeFile(requireExistingDirectory(leftScan.root()), left.relativePath());
        Path rightPath = resolveRelativeFile(requireExistingDirectory(rightScan.root()), right.relativePath());
        BasicFileAttributes leftBefore = requireMatchingFile(leftPath, left);
        BasicFileAttributes rightBefore = requireMatchingFile(rightPath, right);
        boolean equal;
        try (SeekableByteChannel leftChannel = Files.newByteChannel(leftPath, READ_NOFOLLOW_OPTIONS);
             SeekableByteChannel rightChannel = Files.newByteChannel(rightPath, READ_NOFOLLOW_OPTIONS)) {
            equal = channelsEqual(leftChannel, rightChannel);
        }
        BasicFileAttributes leftAfter = readRegularFileAttributes(leftPath);
        BasicFileAttributes rightAfter = readRegularFileAttributes(rightPath);
        if (!sameIdentity(leftBefore, leftAfter) || !sameIdentity(rightBefore, rightAfter)) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED, "工作区文件在比较期间发生变化");
        }
        return equal;
    }

    /** 创建完整的目录副本，而不暴露部分复制的目标。 */
    public WorkspaceCopyResult copyDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
        Path source = requireExistingDirectory(sourceDirectory);
        Path target = normalizeRequiredPath(targetDirectory);
        rejectOverlappingDirectories(source, target);
        Path parent = target.getParent();
        if (parent == null) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "快照目标目录无效");
        }
        ensureDirectory(parent);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw failure(WorkspaceFileSystemException.Reason.TARGET_ALREADY_EXISTS, "快照目标目录已存在");
        }

        Path staging = temporarySibling(target, "copy");
        try {
            WorkspaceCopyResult staged = copyTree(source, staging);
            moveWithoutReplace(staging, target);
            return new WorkspaceCopyResult(target, staged.fileCount(), staged.totalBytes());
        } catch (WorkspaceFileSystemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceFileSystemException.Reason.COPY_FAILED, "复制工作区失败", exception);
        } finally {
            deleteTreeIfExists(staging);
        }
    }

    /** 通过暂存副本替换目录，并在交换失败时恢复原始目录。 */
    public WorkspaceCopyResult replaceDirectory(Path sourceDirectory, Path targetDirectory) throws IOException {
        Path source = requireExistingDirectory(sourceDirectory);
        Path target = requireExistingDirectory(targetDirectory);
        rejectOverlappingDirectories(source, target);
        Path staging = temporarySibling(target, "restore");
        Path displaced = temporarySibling(target, "previous");
        WorkspaceCopyResult staged = copyTree(source, staging);
        boolean targetMoved = false;
        try {
            moveWithoutReplace(target, displaced);
            targetMoved = true;
            try {
                moveWithoutReplace(staging, target);
            } catch (IOException swapFailure) {
                moveWithoutReplace(displaced, target);
                targetMoved = false;
                throw swapFailure;
            }
            targetMoved = false;
            deleteTreeIfExists(displaced);
            return new WorkspaceCopyResult(target, staged.fileCount(), staged.totalBytes());
        } catch (WorkspaceFileSystemException exception) {
            throw exception;
        } catch (IOException exception) {
            throw failure(WorkspaceFileSystemException.Reason.REPLACE_FAILED, "恢复工作区失败", exception);
        } finally {
            deleteTreeIfExists(staging);
            if (targetMoved && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    moveWithoutReplace(displaced, target);
                } catch (IOException ignored) {
                    // 仍以原始异常为准；持久化的快照备份依然存在。
                }
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeIfExists(displaced);
            }
        }
    }

    /** 删除目录树而不遵循任何符号链接。 */
    public void deleteDirectory(Path directory) throws IOException {
        Path normalized = normalizeRequiredPath(directory);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireExistingDirectory(normalized);
        deleteTreeIfExists(normalized);
    }

    /** 列出具有有限结果大小的直接非符号链接子目录。 */
    public List<WorkspaceDirectoryMetadata> listChildDirectories(Path rootDirectory) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        List<WorkspaceDirectoryMetadata> directories = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path child : stream) {
                BasicFileAttributes attributes = Files.readAttributes(
                        child,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    continue;
                }
                if (directories.size() >= properties.getMaxListedDirectories()) {
                    throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                            "快照目录数量超过列表上限");
                }
                directories.add(new WorkspaceDirectoryMetadata(
                        child.getFileName().toString(),
                        attributes.lastModifiedTime().toMillis()
                ));
            }
        }
        directories.sort(Comparator.comparingLong(WorkspaceDirectoryMetadata::lastModifiedTime).reversed()
                .thenComparing(WorkspaceDirectoryMetadata::name));
        return List.copyOf(directories);
    }

    public boolean isDirectory(Path directory) throws IOException {
        if (directory == null) {
            return false;
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        requireExistingDirectory(normalized);
        return true;
    }

    /**
     * 解析现有的直接子目录而不遵循符号链接。
     *
     * <p>这适用于从工件或接收绝对目录的域服务
     * 一个外部命令，必须证明它恰好比配置的信任根低一级。</p>
     */
    public Path resolveExistingDirectChildDirectory(Path rootDirectory, Path childDirectory) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path child = normalizeRequiredPath(childDirectory);
        if (child.equals(root) || child.getParent() == null || !child.getParent().equals(root)) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH,
                    "工作区目录必须是受信任根目录的直接子目录");
        }
        return requireExistingDirectory(child);
    }

    /** 解析工作区根目录下的一个现有常规文件，无需遵循符号链接。 */
    public Path resolveExistingRegularFile(Path rootDirectory, String relativePath) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path file = resolveRelativeFile(root, relativePath);
        readRegularFileAttributes(file);
        return file;
    }

    public Path ensureDirectory(Path directory) throws IOException {
        Path normalized = normalizeRequiredPath(directory);
        validateExistingAncestors(normalized);
        Files.createDirectories(normalized);
        return requireExistingDirectory(normalized);
    }

    private WorkspaceCopyResult copyTree(Path source, Path target) throws IOException {
        Files.createDirectory(target);
        CopyCollector collector = new CopyCollector(source, target);
        try {
            Files.walkFileTree(source, collector);
            return new WorkspaceCopyResult(target, collector.fileCount, collector.totalBytes);
        } catch (IOException exception) {
            deleteTreeIfExists(target);
            throw exception;
        }
    }

    private void copyFile(Path source,
                          Path target,
                          BasicFileAttributes before,
                          CopyCollector collector) throws IOException {
        if (before.size() > properties.getMaxFileBytes()) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_TOO_LARGE, "工作区文件超过快照单文件上限");
        }
        long updatedTotal = safeAdd(collector.totalBytes, before.size(),
                WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED, "工作区快照超过总字节上限");
        if (updatedTotal > properties.getMaxCopyBytes()) {
            throw failure(WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED, "工作区快照超过总字节上限");
        }
        try (SeekableByteChannel input = Files.newByteChannel(source, READ_NOFOLLOW_OPTIONS);
             SeekableByteChannel output = Files.newByteChannel(target, CREATE_NOFOLLOW_OPTIONS)) {
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            long copied = 0L;
            while (true) {
                int bytesRead = input.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }
                buffer.flip();
                copied = safeAdd(copied, buffer.remaining(), WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED,
                        "工作区快照超过总字节上限");
                writeFully(output, buffer);
                buffer.clear();
            }
            if (copied != before.size()) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED, "工作区文件在复制期间发生变化");
            }
        }
        BasicFileAttributes after = readRegularFileAttributes(source);
        if (!sameIdentity(before, after)) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED, "工作区文件在复制期间发生变化");
        }
        Files.setLastModifiedTime(target, FileTime.fromMillis(before.lastModifiedTime().toMillis()));
        collector.totalBytes = updatedTotal;
        collector.fileCount++;
    }

    private BasicFileAttributes requireMatchingFile(Path absolutePath, WorkspaceFileMetadata expected)
            throws IOException {
        BasicFileAttributes actual;
        try {
            actual = readRegularFileAttributes(absolutePath);
        } catch (WorkspaceFileSystemException exception) {
            if (exception.reason() == WorkspaceFileSystemException.Reason.MISSING_FILE
                    || exception.reason() == WorkspaceFileSystemException.Reason.NOT_REGULAR_FILE) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED,
                        "工作区文件自扫描后发生变化", exception);
            }
            throw exception;
        }
        if (actual.size() != expected.size()
                || actual.lastModifiedTime().toMillis() != expected.lastModifiedTime()
                || !compatibleFileKey(expected.fileKey(), actual.fileKey())) {
            throw failure(WorkspaceFileSystemException.Reason.FILE_CHANGED, "工作区文件自扫描后发生变化");
        }
        return actual;
    }

    private BasicFileAttributes readRegularFileAttributes(Path file) throws IOException {
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(
                    file,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
        } catch (NoSuchFileException exception) {
            throw failure(WorkspaceFileSystemException.Reason.MISSING_FILE,
                    "工作区文件不存在", exception);
        }
        if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)) {
            throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                    "工作区文件不能是符号链接");
        }
        if (!attributes.isRegularFile()) {
            throw failure(WorkspaceFileSystemException.Reason.NOT_REGULAR_FILE,
                    "工作区路径不是普通文件");
        }
        return attributes;
    }

    private Path requireExistingDirectory(Path directory) throws IOException {
        Path normalized = normalizeRequiredPath(directory);
        ensureNoSymbolicLinkSegments(normalized);
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(normalized, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw failure(WorkspaceFileSystemException.Reason.MISSING_DIRECTORY, "工作区目录不存在", exception);
        }
        if (attributes.isSymbolicLink() || !attributes.isDirectory() || Files.isSymbolicLink(normalized)) {
            throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, "工作区目录无效或为符号链接");
        }
        return normalized;
    }

    private Path resolveRelativeFile(Path root, String relativePath) throws IOException {
        Path candidate = resolveRelativePath(root, relativePath);
        ensureNoSymbolicLinkSegments(candidate.getParent());
        return candidate;
    }

    private Path resolveRelativePath(Path root, String relativePath) throws WorkspaceFileSystemException {
        if (relativePath == null || relativePath.isBlank()) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区相对路径不能为空");
        }
        Path parsed;
        try {
            parsed = Path.of(relativePath.replace('\\', '/'));
        } catch (InvalidPathException exception) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH,
                    "工作区相对路径格式错误", exception);
        }
        if (parsed.isAbsolute()) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区路径必须是相对路径");
        }
        Path normalizedRelative = parsed.normalize();
        if (normalizedRelative.getNameCount() == 0 || normalizedRelative.startsWith("..")) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区路径超出根目录");
        }
        Path candidate = root.resolve(normalizedRelative).normalize();
        if (!candidate.startsWith(root)) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区路径超出根目录");
        }
        return candidate;
    }

    private void createDirectoriesWithinRoot(Path root, Path directory) throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区目录超出根目录");
        }
        Path current = root;
        Path relative = root.relativize(normalized);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS
                );
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                            "工作区目录包含不安全路径段");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private void validateExistingAncestors(Path path) throws IOException {
        Path cursor = path;
        while (cursor != null && !Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
            cursor = cursor.getParent();
        }
        if (cursor != null) {
            ensureNoSymbolicLinkSegments(cursor);
            BasicFileAttributes attributes = Files.readAttributes(
                    cursor,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                        "工作区目录包含不安全路径段");
            }
        }
    }

    private void ensureNoSymbolicLinkSegments(Path path) throws IOException {
        if (path == null) {
            return;
        }
        Path absolute = path.toAbsolutePath().normalize();
        Path current = absolute.getRoot();
        for (Path part : absolute) {
            current = current == null ? part : current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
                throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                        "工作区路径包含符号链接");
            }
        }
    }

    private void rejectOverlappingDirectories(Path source, Path target) throws WorkspaceFileSystemException {
        if (source.startsWith(target) || target.startsWith(source)) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区源目录和目标目录不能重叠");
        }
    }

    private Path normalizeRequiredPath(Path path) throws WorkspaceFileSystemException {
        if (path == null) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区路径不能为空");
        }
        return path.toAbsolutePath().normalize();
    }

    private Path temporarySibling(Path target, String purpose) {
        return target.getParent().resolve("." + target.getFileName() + "." + purpose + "-" + UUID.randomUUID());
    }

    private void moveWithoutReplace(Path source, Path target) throws IOException {
        int maxAttempts = properties.getPublishMaxAttempts();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                moveWithoutReplaceOnce(source, target);
                return;
            } catch (AccessDeniedException exception) {
                if (attempt >= maxAttempts || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw exception;
                }
                awaitPublishRetry(exception);
            }
        }
    }

    private void moveWithoutReplaceOnce(Path source, Path target) throws IOException {
        try {
            moveOperation.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            moveOperation.move(source, target);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            moveOperation.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            moveOperation.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void awaitPublishRetry(AccessDeniedException accessDeniedException) throws IOException {
        try {
            Thread.sleep(properties.getPublishRetryDelayMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "Interrupted while waiting to retry workspace directory publication"
            );
            interrupted.initCause(accessDeniedException);
            throw interrupted;
        }
    }

    private void deleteTreeIfExists(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    interface MoveOperation {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }

    private boolean channelsEqual(SeekableByteChannel left, SeekableByteChannel right) throws IOException {
        ByteBuffer leftBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        ByteBuffer rightBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        while (true) {
            int leftRead = fillBuffer(left, leftBuffer);
            int rightRead = fillBuffer(right, rightBuffer);
            if (leftRead != rightRead) {
                return false;
            }
            if (leftRead < 0) {
                return true;
            }
            leftBuffer.flip();
            rightBuffer.flip();
            while (leftBuffer.hasRemaining()) {
                if (leftBuffer.get() != rightBuffer.get()) {
                    return false;
                }
            }
        }
    }

    private int fillBuffer(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        buffer.clear();
        int total = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                return total == 0 ? -1 : total;
            }
            if (read == 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private void writeFully(SeekableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private long effectiveReadLimit(long requestedMaxBytes) throws WorkspaceFileSystemException {
        if (requestedMaxBytes <= 0) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH, "工作区读取上限必须大于 0");
        }
        return Math.min(requestedMaxBytes, properties.getMaxReadableFileBytes());
    }

    private long effectiveFileLimit(long requestedMaxBytes) throws WorkspaceFileSystemException {
        if (requestedMaxBytes <= 0) {
            throw failure(WorkspaceFileSystemException.Reason.INVALID_PATH,
                    "工作区文件资源上限必须大于 0");
        }
        return Math.min(requestedMaxBytes, properties.getMaxInteractiveFileBytes());
    }

    private Path interactiveTemporarySibling(Path target) {
        return target.getParent().resolve(
                INTERACTIVE_TEMP_FILE_PREFIX + target.getFileName() + "-" + UUID.randomUUID() + ".tmp"
        );
    }

    private ReentrantLock mutationLockFor(Path target) {
        int lockIndex = Math.floorMod(target.toAbsolutePath().normalize().toString().hashCode(), mutationLocks.length);
        return mutationLocks[lockIndex];
    }

    private ReentrantLock[] createMutationLocks() {
        ReentrantLock[] locks = new ReentrantLock[MUTATION_LOCK_STRIPES];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new ReentrantLock();
        }
        return locks;
    }

    private long safeAdd(long left,
                         long right,
                         WorkspaceFileSystemException.Reason reason,
                         String message) throws WorkspaceFileSystemException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw failure(reason, message, exception);
        }
    }

    private WorkspaceFileMetadata metadata(Path root, Path file, BasicFileAttributes attributes) {
        return new WorkspaceFileMetadata(
                root.relativize(file).toString().replace('\\', '/'),
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
                attributes.fileKey()
        );
    }

    private boolean compatibleFileKey(Object expected, Object actual) {
        return expected == null || actual == null || expected.equals(actual);
    }

    private boolean sameIdentity(BasicFileAttributes before, BasicFileAttributes after) {
        return before.size() == after.size()
                && before.lastModifiedTime().equals(after.lastModifiedTime())
                && compatibleFileKey(before.fileKey(), after.fileKey());
    }

    private boolean shouldIgnoreDirectory(Path directory) {
        Path fileName = directory.getFileName();
        return fileName != null && IGNORED_DIRECTORY_NAMES.contains(fileName.toString().toLowerCase(Locale.ROOT));
    }

    private boolean shouldIgnoreScanFile(Path file) {
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return SENSITIVE_SCAN_FILE_NAMES.contains(fileName)
                || fileName.startsWith(".env.")
                || fileName.endsWith(".log")
                || fileName.endsWith(".tmp")
                || fileName.endsWith(".cache");
    }

    private WorkspaceFileSystemException failure(WorkspaceFileSystemException.Reason reason, String message) {
        return new WorkspaceFileSystemException(reason, message);
    }

    private WorkspaceFileSystemException failure(WorkspaceFileSystemException.Reason reason,
                                                 String message,
                                                 Throwable cause) {
        return new WorkspaceFileSystemException(reason, message, cause);
    }

    public record WorkspaceFileMetadata(String relativePath,
                                        long size,
                                        long lastModifiedTime,
                                        Object fileKey) {

        public WorkspaceFileMetadata {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath must not be blank");
            }
        }

        public String fileName() {
            Path path = Path.of(relativePath);
            Path fileName = path.getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }

    public record WorkspaceScan(Path root, List<WorkspaceFileMetadata> files, long totalBytes) {

        public WorkspaceScan {
            root = Objects.requireNonNull(root, "root must not be null").toAbsolutePath().normalize();
            files = files == null ? List.of() : List.copyOf(files);
        }
    }

    public record WorkspaceCopyResult(Path targetDirectory, int fileCount, long totalBytes) {
    }

    public record WorkspaceDirectoryMetadata(String name, long lastModifiedTime) {
    }

    public record WorkspaceTreeNode(String name,
                                    String relativePath,
                                    boolean directory,
                                    long size,
                                    List<WorkspaceTreeNode> children) {

        public WorkspaceTreeNode {
            name = Objects.requireNonNull(name, "name must not be null");
            relativePath = Objects.requireNonNull(relativePath, "relativePath must not be null");
            children = children == null ? List.of() : List.copyOf(children);
        }
    }

    @FunctionalInterface
    public interface WorkspaceTreeFilter {

        boolean include(String relativePath, String name, boolean directory);
    }

    private final class TreeCollector {

        private final Path root;
        private final int maxDepth;
        private final WorkspaceTreeFilter filter;
        private int fileCount;
        private int directoryCount;

        private TreeCollector(Path root, int maxDepth, WorkspaceTreeFilter filter) {
            this.root = root;
            this.maxDepth = maxDepth;
            this.filter = filter;
        }

        private List<WorkspaceTreeNode> listDirectory(Path directory, int childDepth) throws IOException {
            List<WorkspaceTreeNode> nodes = new ArrayList<>();
            try (DirectoryStream<Path> children = Files.newDirectoryStream(directory)) {
                for (Path child : children) {
                    BasicFileAttributes attributes = Files.readAttributes(
                            child,
                            BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS
                    );
                    if (attributes.isSymbolicLink() || Files.isSymbolicLink(child)) {
                        continue;
                    }
                    boolean childDirectory = attributes.isDirectory();
                    if (!childDirectory && !attributes.isRegularFile()) {
                        continue;
                    }
                    String name = child.getFileName().toString();
                    String relativePath = root.relativize(child).toString().replace('\\', '/');
                    if (name.startsWith(INTERACTIVE_TEMP_FILE_PREFIX)
                            || !filter.include(relativePath, name, childDirectory)) {
                        continue;
                    }
                    if (childDirectory) {
                        if (directoryCount >= properties.getMaxListedDirectories()) {
                            throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                                    "工作区文件树目录数量超过列表上限");
                        }
                        directoryCount++;
                        List<WorkspaceTreeNode> nested = childDepth < maxDepth
                                ? listDirectory(child, childDepth + 1)
                                : List.of();
                        nodes.add(new WorkspaceTreeNode(name, relativePath, true, 0L, nested));
                    } else {
                        if (fileCount >= properties.getMaxFiles()) {
                            throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                                    "工作区文件树文件数量超过列表上限");
                        }
                        fileCount++;
                        nodes.add(new WorkspaceTreeNode(
                                name,
                                relativePath,
                                false,
                                attributes.size(),
                                List.of()
                        ));
                    }
                }
            }
            nodes.sort(Comparator
                    .comparing(WorkspaceTreeNode::directory).reversed()
                    .thenComparing(WorkspaceTreeNode::name, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(WorkspaceTreeNode::name));
            return List.copyOf(nodes);
        }
    }

    private final class ScanCollector extends SimpleFileVisitor<Path> {

        private final Path root;
        private final List<WorkspaceFileMetadata> files = new ArrayList<>();
        private long totalBytes;

        private ScanCollector(Path root) {
            this.root = root;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                return directory.equals(root) ? unsafeRoot() : FileVisitResult.SKIP_SUBTREE;
            }
            int depth = root.relativize(directory).getNameCount();
            if (depth > properties.getMaxDirectoryDepth()) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                        "工作区目录深度超过扫描上限");
            }
            if (!directory.equals(root) && shouldIgnoreDirectory(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(file)
                    || !attributes.isRegularFile() || shouldIgnoreScanFile(file)) {
                return FileVisitResult.CONTINUE;
            }
            if (files.size() >= properties.getMaxFiles()) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                        "工作区文件数量超过扫描上限");
            }
            totalBytes = safeAdd(totalBytes, attributes.size(), WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED,
                    "工作区总字节数超过扫描上限");
            if (totalBytes > properties.getMaxScannedBytes()) {
                throw failure(WorkspaceFileSystemException.Reason.BYTE_LIMIT_EXCEEDED,
                        "工作区总字节数超过扫描上限");
            }
            files.add(metadata(root, file, attributes));
            return FileVisitResult.CONTINUE;
        }

        private FileVisitResult unsafeRoot() throws WorkspaceFileSystemException {
            throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                    "工作区根目录不能是符号链接");
        }
    }

    private final class CopyCollector extends SimpleFileVisitor<Path> {

        private final Path sourceRoot;
        private final Path targetRoot;
        private int fileCount;
        private long totalBytes;

        private CopyCollector(Path sourceRoot, Path targetRoot) {
            this.sourceRoot = sourceRoot;
            this.targetRoot = targetRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                return directory.equals(sourceRoot) ? unsafeRoot() : FileVisitResult.SKIP_SUBTREE;
            }
            int depth = sourceRoot.relativize(directory).getNameCount();
            if (depth > properties.getMaxDirectoryDepth()) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                        "工作区目录深度超过快照上限");
            }
            if (!directory.equals(sourceRoot) && shouldIgnoreDirectory(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            if (!directory.equals(sourceRoot)) {
                Files.createDirectory(targetRoot.resolve(sourceRoot.relativize(directory)));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || !attributes.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            if (fileCount >= properties.getMaxFiles()) {
                throw failure(WorkspaceFileSystemException.Reason.FILE_LIMIT_EXCEEDED,
                        "工作区文件数量超过快照上限");
            }
            Path target = targetRoot.resolve(sourceRoot.relativize(file));
            copyFile(file, target, attributes, this);
            return FileVisitResult.CONTINUE;
        }

        private FileVisitResult unsafeRoot() throws WorkspaceFileSystemException {
            throw failure(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK,
                    "工作区根目录不能是符号链接");
        }
    }
}
