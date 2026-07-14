package com.rush.rushaicodemother.infrastructure.filesystem;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
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
 * Deep file-system module for workspace scans, bounded reads, and transactional directory copies.
 *
 * <p>The implementation never follows symbolic links. Every read is checked against metadata captured
 * by the scan that selected the file, and snapshot copies are staged before becoming visible.</p>
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

    public WorkspaceFileSystemService(WorkspaceFileSystemProperties properties) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.mutationLocks = createMutationLocks();
    }

    /** Scans a project once and returns stable relative-path metadata for downstream consumers. */
    public WorkspaceScan scanProject(Path rootDirectory) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        ScanCollector collector = new ScanCollector(root);
        Files.walkFileTree(root, collector);
        collector.files.sort(Comparator.comparing(WorkspaceFileMetadata::relativePath));
        return new WorkspaceScan(root, collector.files, collector.totalBytes);
    }

    /** Lists a bounded interactive tree while preserving visible empty directories. */
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

    /** Resolves one existing regular file and captures the identity required by later safe reads or writes. */
    public WorkspaceFileMetadata resolveExistingFile(Path rootDirectory, String relativePath) throws IOException {
        Path root = requireExistingDirectory(rootDirectory);
        Path absolutePath = resolveRelativeFile(root, relativePath);
        BasicFileAttributes attributes = readRegularFileAttributes(absolutePath);
        return metadata(root, absolutePath, attributes);
    }

    /** Reads an explicitly resolved UTF-8 file under a caller-provided resource limit. */
    public String readUtf8(Path rootDirectory, WorkspaceFileMetadata file, long requestedMaxBytes)
            throws IOException {
        Objects.requireNonNull(file, "file must not be null");
        return readUtf8File(rootDirectory, file, effectiveFileLimit(requestedMaxBytes));
    }

    /**
     * Replaces an existing UTF-8 file only if it still has the expected identity.
     *
     * <p>Writes are serialized per path within this process, forced to durable storage before replacement,
     * and use an atomic move whenever the underlying file system supports it.</p>
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

    /** Reads a scanned file as UTF-8 without following links or accepting a changed file identity. */
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

    /** Reads an optional generated UTF-8 file below a workspace root. */
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

    /** Writes a generated UTF-8 file through a sibling temporary file and atomic replacement when supported. */
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

    /** Compares two scanned files without loading either complete file into memory. */
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

    /** Creates a complete directory copy without exposing a partially copied target. */
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

    /** Replaces a directory through a staged copy and restores the original directory if the swap fails. */
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
                    // The original exception remains authoritative; a durable snapshot backup still exists.
                }
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                deleteTreeIfExists(displaced);
            }
        }
    }

    /** Deletes a directory tree without following any symbolic link. */
    public void deleteDirectory(Path directory) throws IOException {
        Path normalized = normalizeRequiredPath(directory);
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        requireExistingDirectory(normalized);
        deleteTreeIfExists(normalized);
    }

    /** Lists immediate non-symbolic-link child directories with a bounded result size. */
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
     * Resolves an existing direct child directory without following symbolic links.
     *
     * <p>This is intended for domain services that receive an absolute directory from an artifact or
     * an external command and must prove that it is exactly one level below a configured trust root.</p>
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
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
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
