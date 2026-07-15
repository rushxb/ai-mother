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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Performs bounded artifact directory copies without following symbolic links.
 *
 * <p>The source is inspected before and after copying, and the staged target is inspected
 * independently. A result is accepted only when the source remained stable and the target layout
 * matches the source manifest.</p>
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

    void copy(Path sourceDirectory, Path targetDirectory, ArtifactCopyProfile profile)
            throws IOException, InterruptedException {
        Objects.requireNonNull(profile, "profile must not be null");
        Path sourceRoot = requireExistingDirectory(sourceDirectory, "artifact source directory");
        Path targetRoot = requireNewTarget(targetDirectory);
        rejectOverlappingTrees(sourceRoot, targetRoot);
        TreeManifest sourceBefore = inspectTree(sourceRoot, profile);

        try {
            Files.createDirectory(targetRoot);
            if (windows) {
                robocopyDirectoryCopier.copy(
                        sourceRoot,
                        targetRoot,
                        profile.excludedDirectories(),
                        profile.excludedFiles()
                );
            } else {
                copyWithNio(sourceRoot, targetRoot, profile);
            }

            TreeManifest sourceAfter = inspectTree(sourceRoot, profile);
            if (!sourceBefore.equals(sourceAfter)) {
                throw new ArtifactCopyException(
                        ArtifactCopyException.Reason.SOURCE_CHANGED,
                        "artifact source changed during copying; retry the operation"
                );
            }
            TreeManifest targetManifest = inspectTree(targetRoot, ArtifactCopyProfile.DEPLOYMENT);
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

    private void copyWithNio(Path sourceRoot, Path targetRoot, ArtifactCopyProfile profile) throws IOException {
        Files.walkFileTree(sourceRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
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

    private TreeManifest inspectTree(Path root, ArtifactCopyProfile profile) throws IOException {
        ManifestCollector collector = new ManifestCollector(root, profile);
        Files.walkFileTree(root, collector);
        return collector.toManifest();
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
        private final Set<String> directories = new LinkedHashSet<>();
        private final Map<String, FileFingerprint> files = new LinkedHashMap<>();
        private long totalBytes;

        private ManifestCollector(Path root, ArtifactCopyProfile profile) {
            this.root = root;
            this.profile = profile;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
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
