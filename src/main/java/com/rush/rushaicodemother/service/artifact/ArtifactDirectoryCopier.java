package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * 执行有界工件目录复制，无需遵循符号链接。
 *
 * <p>复制前后检查源，检查暂存目标
 * 独立。仅当源保持稳定并且目标布局保持稳定时才接受结果
 * 与源清单匹配。</p>
 */
@Component
public class ArtifactDirectoryCopier {

    private final ArtifactLifecycleProperties properties;
    private final RobocopyDirectoryCopier robocopyDirectoryCopier;
    private final boolean windows;

    @Autowired
    public ArtifactDirectoryCopier(
            ArtifactLifecycleProperties properties,
            RobocopyDirectoryCopier robocopyDirectoryCopier
    ) {
        this(
                properties,
                robocopyDirectoryCopier,
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows")
        );
    }

    ArtifactDirectoryCopier(
            ArtifactLifecycleProperties properties,
            RobocopyDirectoryCopier robocopyDirectoryCopier,
            boolean windows
    ) {
        this.properties = Objects.requireNonNull(properties, "properties must not be null");
        this.robocopyDirectoryCopier = Objects.requireNonNull(
                robocopyDirectoryCopier,
                "robocopyDirectoryCopier must not be null"
        );
        this.windows = windows;
    }

    /** 将源代码复制到独立的执行工作区中，无需依赖/构建缓存。 */
    public void copyExecutionWorkspace(Path sourceDirectory, Path targetDirectory)
            throws IOException, InterruptedException {
        copyExecutionWorkspace(
                sourceDirectory,
                targetDirectory,
                properties.getExecutionWorkspaceCopyTimeout(),
                () -> false
        );
    }

    /**
     * 在调用者的任务范围挂钟和取消策略下复制执行工作区。
     */
    public void copyExecutionWorkspace(Path sourceDirectory,
                                       Path targetDirectory,
                                       Duration timeout,
                                       BooleanSupplier cancellationRequested)
            throws IOException, InterruptedException {
        copy(
                sourceDirectory,
                targetDirectory,
                ArtifactCopyProfile.EXECUTION_WORKSPACE,
                CopyControl.start(timeout, cancellationRequested)
        );
    }

    void copy(Path sourceDirectory, Path targetDirectory, ArtifactCopyProfile profile)
            throws IOException, InterruptedException {
        copy(sourceDirectory, targetDirectory, profile, null);
    }

    private void copy(Path sourceDirectory,
                      Path targetDirectory,
                      ArtifactCopyProfile profile,
                      CopyControl control)
            throws IOException, InterruptedException {
        Objects.requireNonNull(profile, "profile must not be null");
        check(control);
        Path sourceRoot = requireExistingDirectory(sourceDirectory, "artifact source directory");
        Path targetRoot = requireNewTarget(targetDirectory);
        rejectOverlappingTrees(sourceRoot, targetRoot);
        TreeManifest sourceBefore = inspectTree(sourceRoot, profile, control);

        try {
            check(control);
            Files.createDirectory(targetRoot);
            if (windows) {
                if (control == null) {
                    robocopyDirectoryCopier.copy(
                            sourceRoot,
                            targetRoot,
                            profile.excludedDirectories(),
                            profile.excludedFiles()
                    );
                } else {
                    robocopyDirectoryCopier.copy(
                            sourceRoot,
                            targetRoot,
                            profile.excludedDirectories(),
                            profile.excludedFiles(),
                            control.remainingTimeout(),
                            control::cancellationRequested
                    );
                }
            } else {
                copyWithNio(sourceRoot, targetRoot, profile, control);
            }

            check(control);
            TreeManifest sourceAfter = inspectTree(sourceRoot, profile, control);
            if (!sourceBefore.equals(sourceAfter)) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.SOURCE_CHANGED,
                        "artifact source changed during copying; retry the operation"
                );
            }
            TreeManifest targetManifest = inspectTree(
                    targetRoot,
                    ArtifactCopyProfile.DEPLOYMENT,
                    control
            );
            if (!sourceBefore.sameLayout(targetManifest)) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.INCOMPLETE_COPY,
                        "artifact copy is incomplete; staged output was rejected"
                );
            }
        } catch (IOException | InterruptedException | RuntimeException exception) {
            deleteTreeQuietly(targetRoot, exception);
            throw exception;
        }
    }

    private void copyWithNio(Path sourceRoot,
                             Path targetRoot,
                             ArtifactCopyProfile profile,
                             CopyControl control) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                check(control);
                rejectSymbolicLink(directory, attributes, sourceRoot);
                if (!directory.equals(sourceRoot) && profile.excludesDirectory(fileName(directory))) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!directory.equals(sourceRoot)) {
                    Files.createDirectory(targetRoot.resolve(sourceRoot.relativize(directory)));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                check(control);
                rejectUnsafeFile(file, attributes, sourceRoot);
                if (profile.excludesFile(fileName(file))) {
                    return FileVisitResult.CONTINUE;
                }
                Path targetFile = targetRoot.resolve(sourceRoot.relativize(file));
                Files.copy(
                        file,
                        targetFile,
                        LinkOption.NOFOLLOW_LINKS,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private TreeManifest inspectTree(Path root,
                                     ArtifactCopyProfile profile,
                                     CopyControl control) throws IOException {
        ManifestCollector collector = new ManifestCollector(root, profile, control);
        Files.walkFileTree(root, collector);
        return collector.toManifest();
    }

    private void check(CopyControl control) throws ArtifactCopyException {
        if (control != null) {
            control.check();
        }
    }

    private Path requireExistingDirectory(Path directory, String label) throws IOException {
        if (directory == null) {
            throw new ArtifactCopyException(ArtifactCopyException.Reason.INVALID_PATH, label + " must not be null");
        }
        Path normalized = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(normalized)) {
            throw unsafeSymbolicLink(normalized, normalized);
        }
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    label + " does not exist or is not a directory"
            );
        }
        return normalized.toRealPath();
    }

    private Path requireNewTarget(Path targetDirectory) throws IOException {
        if (targetDirectory == null) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    "artifact copy target must not be null"
            );
        }
        Path target = targetDirectory.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null
                || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(parent)) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    "artifact copy target parent is unavailable"
            );
        }
        Path canonicalTarget = parent.toRealPath().resolve(target.getFileName());
        if (Files.exists(canonicalTarget, LinkOption.NOFOLLOW_LINKS)) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    "artifact copy target already exists"
            );
        }
        return canonicalTarget;
    }

    private void rejectOverlappingTrees(Path sourceRoot, Path targetRoot) throws ArtifactCopyException {
        if (targetRoot.startsWith(sourceRoot) || sourceRoot.startsWith(targetRoot)) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    "artifact source and target directories must not overlap"
            );
        }
    }

    private void rejectSymbolicLink(Path path, BasicFileAttributes attributes, Path root) throws IOException {
        if (attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
            throw unsafeSymbolicLink(root, path);
        }
    }

    private void rejectUnsafeFile(Path file, BasicFileAttributes attributes, Path root) throws IOException {
        rejectSymbolicLink(file, attributes, root);
        if (!attributes.isRegularFile()) {
            throw new ArtifactCopyException(
                    ArtifactCopyException.Reason.INVALID_PATH,
                    "artifact tree contains an unsupported special file: " + relativeDisplay(root, file)
            );
        }
    }

    private ArtifactCopyException unsafeSymbolicLink(Path root, Path link) {
        return new ArtifactCopyException(
                ArtifactCopyException.Reason.UNSAFE_SYMBOLIC_LINK,
                "artifact tree must not contain symbolic links: " + relativeDisplay(root, link)
        );
    }

    private String relativeDisplay(Path root, Path path) {
        try {
            Path relative = root.equals(path) ? path.getFileName() : root.relativize(path);
            return relative == null ? path.toString() : relative.toString().replace('\\', '/');
        } catch (IllegalArgumentException exception) {
            return path.toString();
        }
    }

    private String fileName(Path path) {
        Path fileName = path.getFileName();
        return fileName == null ? "" : fileName.toString();
    }

    private void deleteTreeQuietly(Path target, Exception primaryFailure) {
        try {
            if (target == null || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(target) || !Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(target);
                return;
            }
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
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
        } catch (IOException | RuntimeException cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private final class ManifestCollector extends SimpleFileVisitor<Path> {

        private final Path root;
        private final ArtifactCopyProfile profile;
        private final CopyControl control;
        private final Set<String> directories = new LinkedHashSet<>();
        private final Map<String, FileFingerprint> files = new LinkedHashMap<>();
        private long totalBytes;

        private ManifestCollector(Path root, ArtifactCopyProfile profile, CopyControl control) {
            this.root = root;
            this.profile = profile;
            this.control = control;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            check(control);
            rejectSymbolicLink(directory, attributes, root);
            if (!directory.equals(root) && profile.excludesDirectory(fileName(directory))) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            int depth = directory.equals(root) ? 0 : root.relativize(directory).getNameCount();
            if (depth > properties.getMaxDirectoryDepth()) {
                throw limitExceeded("artifact directory depth exceeds limit: " + properties.getMaxDirectoryDepth());
            }
            if (!directory.equals(root)) {
                if (directories.size() >= properties.getMaxDirectories()) {
                    throw limitExceeded("artifact directory count exceeds limit: " + properties.getMaxDirectories());
                }
                directories.add(relativePath(directory));
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            check(control);
            rejectUnsafeFile(file, attributes, root);
            if (profile.excludesFile(fileName(file))) {
                return FileVisitResult.CONTINUE;
            }
            if (files.size() >= properties.getMaxFiles()) {
                throw limitExceeded("artifact file count exceeds limit: " + properties.getMaxFiles());
            }
            long fileBytes = attributes.size();
            if (fileBytes > properties.getMaxFileBytes()) {
                throw limitExceeded(
                        "artifact file size exceeds limit: " + relativeDisplay(root, file)
                                + " (" + properties.getMaxFileBytes() + " bytes)"
                );
            }
            if (fileBytes > properties.getMaxTotalBytes() - totalBytes) {
                throw limitExceeded("artifact total bytes exceed limit: " + properties.getMaxTotalBytes());
            }
            totalBytes += fileBytes;
            files.put(relativePath(file), new FileFingerprint(
                    fileBytes,
                    attributes.lastModifiedTime(),
                    attributes.fileKey() == null ? null : attributes.fileKey().toString()
            ));
            return FileVisitResult.CONTINUE;
        }

        private String relativePath(Path path) {
            return root.relativize(path).toString().replace('\\', '/');
        }

        private TreeManifest toManifest() {
            return new TreeManifest(Set.copyOf(directories), Map.copyOf(files), totalBytes);
        }
    }

    private ArtifactCopyException limitExceeded(String message) {
        return new ArtifactCopyException(ArtifactCopyException.Reason.LIMIT_EXCEEDED, message);
    }

    private static final class CopyControl {

        private final long startedAtNanos;
        private final long timeoutNanos;
        private final BooleanSupplier cancellationRequested;

        private CopyControl(long timeoutNanos, BooleanSupplier cancellationRequested) {
            this.startedAtNanos = System.nanoTime();
            this.timeoutNanos = timeoutNanos;
            this.cancellationRequested = cancellationRequested;
        }

        private static CopyControl start(Duration timeout, BooleanSupplier cancellationRequested) {
            Objects.requireNonNull(timeout, "artifact copy timeout must not be null");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("artifact copy timeout must be greater than zero");
            }
            long timeoutNanos;
            try {
                timeoutNanos = timeout.toNanos();
            } catch (ArithmeticException ignored) {
                timeoutNanos = Long.MAX_VALUE;
            }
            return new CopyControl(
                    timeoutNanos,
                    cancellationRequested == null ? () -> false : cancellationRequested
            );
        }

        private void check() throws ArtifactCopyException {
            if (Thread.currentThread().isInterrupted()) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.INTERRUPTED,
                        "artifact copy thread was interrupted"
                );
            }
            if (cancellationRequested.getAsBoolean()) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.CANCELLED,
                        "artifact copy was cancelled"
                );
            }
            if (elapsedNanos() >= timeoutNanos) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.TIMED_OUT,
                        "artifact copy exceeded its wall-clock timeout"
                );
            }
        }

        private Duration remainingTimeout() throws ArtifactCopyException {
            check();
            long remainingNanos = timeoutNanos - elapsedNanos();
            if (remainingNanos <= 0) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.TIMED_OUT,
                        "artifact copy exceeded its wall-clock timeout"
                );
            }
            return Duration.ofNanos(remainingNanos);
        }

        private boolean cancellationRequested() {
            return Thread.currentThread().isInterrupted() || cancellationRequested.getAsBoolean();
        }

        private long elapsedNanos() {
            return System.nanoTime() - startedAtNanos;
        }
    }

    private record FileFingerprint(long size, FileTime lastModifiedTime, String fileKey) {
    }

    private record TreeManifest(
            Set<String> directories,
            Map<String, FileFingerprint> files,
            long totalBytes
    ) {
        private boolean sameLayout(TreeManifest other) {
            if (other == null || totalBytes != other.totalBytes || !directories.equals(other.directories)) {
                return false;
            }
            if (!files.keySet().equals(other.files.keySet())) {
                return false;
            }
            return files.entrySet().stream().allMatch(entry ->
                    entry.getValue().size() == other.files.get(entry.getKey()).size());
        }
    }
}
