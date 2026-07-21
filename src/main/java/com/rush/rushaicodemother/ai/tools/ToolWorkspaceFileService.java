package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceException;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.patch.PatchWorkspaceTarget;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Deep workspace module for bounded, symbolic-link-safe file access performed by AI tools.
 *
 * <p>Mutations still flow through {@code ToolExecutionGateway}; this module owns only path resolution,
 * precondition checks, bounded reads, and bounded directory traversal.</p>
 */
@Component
@RequiredArgsConstructor
public class ToolWorkspaceFileService {

    private static final Set<String> IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", ".mvn", "node_modules", "dist", "build", "target",
            "coverage", ".ai-code-index", ".ds_store"
    );

    private static final Set<String> IGNORED_EXTENSIONS = Set.of(
            ".log", ".tmp", ".cache", ".lock"
    );

    private static final Comparator<DirectoryEntry> DIRECTORY_ENTRY_ORDER = Comparator
            .comparingInt(DirectoryEntry::depth)
            .thenComparing(DirectoryEntry::directory, Comparator.reverseOrder())
            .thenComparing(DirectoryEntry::relativePath);

    private final ToolPathSupport toolPathSupport;
    private final PatchWorkspaceFileService patchWorkspaceFileService;
    private final AiToolWorkspaceProperties properties;

    public Path resolveProjectRoot(Long appId) {
        Path configuredRoot = toolPathSupport.resolveProjectRoot(appId);
        try {
            return patchWorkspaceFileService.resolveProjectRoot(configuredRoot);
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    /** Returns the task identity that bounds external processes started by an AI tool. */
    public String requireTaskId(Long appId) {
        return toolPathSupport.resolveTaskId(appId);
    }

    /** Resolves an existing directory inside the bound project workspace. */
    public ToolWorkspaceDirectory resolveDirectory(Long appId, String relativePath) {
        Path projectRoot = resolveProjectRoot(appId);
        if (relativePath == null || relativePath.isBlank()) {
            return ToolWorkspaceDirectory.projectRoot(projectRoot);
        }
        try {
            PatchWorkspaceTarget target = patchWorkspaceFileService.resolve(projectRoot, relativePath);
            if (!patchWorkspaceFileService.isDirectory(target)) {
                throw new ToolInputException("目录不存在或不是目录");
            }
            return ToolWorkspaceDirectory.child(target.relativePath(), projectRoot, target);
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    /** Resolves a file target; the target may be absent when a write intends to create it. */
    public ToolWorkspaceFile resolveFile(Long appId, String relativePath) {
        Path projectRoot = resolveProjectRoot(appId);
        try {
            PatchWorkspaceTarget target = patchWorkspaceFileService.resolve(projectRoot, relativePath);
            return new ToolWorkspaceFile(target.relativePath(), target);
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    /** Resolves a child file without allowing the child path to escape its selected project directory. */
    public ToolWorkspaceFile resolveFile(ToolWorkspaceDirectory directory, String relativePath) {
        ToolWorkspaceDirectory requiredDirectory = requireDirectory(directory);
        String normalizedChildPath = toolPathSupport.normalizeRelativePath(relativePath);
        try {
            revalidateDirectory(requiredDirectory);
            String workspaceRelativePath = requiredDirectory.relativePath().isBlank()
                    ? normalizedChildPath
                    : requiredDirectory.relativePath() + "/" + normalizedChildPath;
            PatchWorkspaceTarget target = patchWorkspaceFileService.resolve(
                    requiredDirectory.projectRoot(),
                    workspaceRelativePath
            );
            if (!target.absolutePath().startsWith(requiredDirectory.absolutePath())) {
                throw new PatchWorkspaceException("path_outside_project");
            }
            return new ToolWorkspaceFile(target.relativePath(), target);
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    public boolean exists(ToolWorkspaceFile file) {
        try {
            return patchWorkspaceFileService.exists(requireFile(file).target());
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    public boolean isRegularFile(ToolWorkspaceFile file) {
        try {
            return patchWorkspaceFileService.isRegularFile(requireFile(file).target());
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    public String readUtf8(ToolWorkspaceFile file) {
        try {
            return patchWorkspaceFileService.readUtf8(
                    requireFile(file).target(),
                    properties.getMaxReadableFileBytes()
            );
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        } catch (IOException exception) {
            throw new ToolInputException("文件读取失败，请稍后重试", exception);
        }
    }

    public DirectoryListing listDirectory(Long appId, String relativeDirectoryPath) {
        Path projectRoot = resolveProjectRoot(appId);
        Path directory = projectRoot;
        if (relativeDirectoryPath != null && !relativeDirectoryPath.isBlank()) {
            ToolWorkspaceFile target = resolveFile(appId, relativeDirectoryPath);
            if (!exists(target) || !isDirectory(target)) {
                throw new ToolInputException("目录不存在或不是目录");
            }
            directory = target.target().absolutePath();
        }

        DirectoryCollector collector = new DirectoryCollector(projectRoot, directory);
        int traversalDepth = Math.addExact(properties.getMaxDirectoryDepth(), 1);
        try {
            Files.walkFileTree(
                    directory,
                    EnumSet.noneOf(FileVisitOption.class),
                    traversalDepth,
                    collector
            );
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        } catch (IOException | SecurityException exception) {
            throw new ToolInputException("目录读取失败，请稍后重试", exception);
        }

        List<DirectoryEntry> entries = collector.entries.stream()
                .sorted(DIRECTORY_ENTRY_ORDER)
                .toList();
        return new DirectoryListing(entries, collector.truncated);
    }

    private boolean isDirectory(ToolWorkspaceFile file) {
        try {
            return patchWorkspaceFileService.isDirectory(requireFile(file).target());
        } catch (PatchWorkspaceException exception) {
            throw mapWorkspaceException(exception);
        }
    }

    private ToolWorkspaceFile requireFile(ToolWorkspaceFile file) {
        if (file == null) {
            throw new ToolInputException("文件路径不能为空");
        }
        return file;
    }

    private ToolWorkspaceDirectory requireDirectory(ToolWorkspaceDirectory directory) {
        if (directory == null) {
            throw new ToolInputException("目录路径不能为空");
        }
        return directory;
    }

    private void revalidateDirectory(ToolWorkspaceDirectory directory) throws PatchWorkspaceException {
        if (directory.target() == null) {
            patchWorkspaceFileService.resolveProjectRoot(directory.projectRoot());
            return;
        }
        if (!patchWorkspaceFileService.isDirectory(directory.target())) {
            throw new PatchWorkspaceException("unsafe_target_path");
        }
    }

    private PatchWorkspaceTarget revalidateVisitedPath(Path projectRoot, Path visitedPath)
            throws PatchWorkspaceException {
        Path normalizedPath = visitedPath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(projectRoot)) {
            throw new PatchWorkspaceException("path_outside_project");
        }
        String relativePath = projectRoot.relativize(normalizedPath).toString().replace('\\', '/');
        if (relativePath.isBlank()) {
            patchWorkspaceFileService.resolveProjectRoot(projectRoot);
            return null;
        }
        return patchWorkspaceFileService.resolve(projectRoot, relativePath);
    }

    private ToolInputException mapWorkspaceException(PatchWorkspaceException exception) {
        String publicMessage = switch (exception.reason()) {
            case "invalid_path" -> "文件路径格式错误";
            case "path_outside_project" -> "非法路径，超出当前项目目录范围";
            case "symbolic_link_not_allowed", "unsafe_target_path", "path_changed_during_patch" ->
                    "项目路径不能经过符号链接或不安全路径";
            case "project_root_missing", "unsafe_project_root" -> "项目工作区不存在或不安全";
            case "target_file_too_large" -> "文件超过可读取大小限制";
            case "target_not_regular_file" -> "文件不存在或不是普通文件";
            case "invalid_read_limit" -> "文件读取限制配置无效";
            default -> "项目文件访问失败，请稍后重试";
        };
        return new ToolInputException(publicMessage, exception);
    }

    private static boolean shouldIgnore(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        if (IGNORED_NAMES.contains(fileName) || ".env".equals(fileName) || fileName.startsWith(".env.")) {
            return true;
        }
        return IGNORED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }

    public record ToolWorkspaceFile(String relativePath, PatchWorkspaceTarget target) {

        public ToolWorkspaceFile {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath must not be blank");
            }
            Objects.requireNonNull(target, "target");
        }

        public Path projectRoot() {
            return target.realRoot();
        }

        public Path absolutePath() {
            return target.absolutePath();
        }

        public String fileName() {
            Path fileName = target.absolutePath().getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }

    public record ToolWorkspaceDirectory(
            String relativePath,
            Path projectRoot,
            PatchWorkspaceTarget target
    ) {

        public ToolWorkspaceDirectory {
            relativePath = relativePath == null ? "" : relativePath;
            Objects.requireNonNull(projectRoot, "projectRoot");
            if (relativePath.isBlank() != (target == null)) {
                throw new IllegalArgumentException("only the project root may omit a workspace target");
            }
        }

        private static ToolWorkspaceDirectory projectRoot(Path projectRoot) {
            return new ToolWorkspaceDirectory("", projectRoot, null);
        }

        private static ToolWorkspaceDirectory child(
                String relativePath,
                Path projectRoot,
                PatchWorkspaceTarget target
        ) {
            return new ToolWorkspaceDirectory(relativePath, projectRoot, target);
        }

        public Path absolutePath() {
            return target == null ? projectRoot : target.absolutePath();
        }

        public String displayPath() {
            return relativePath.isBlank() ? "." : relativePath;
        }
    }

    public record DirectoryEntry(String relativePath, boolean directory) {

        public DirectoryEntry {
            if (relativePath == null || relativePath.isBlank()) {
                throw new IllegalArgumentException("relativePath must not be blank");
            }
        }

        public int depth() {
            return Path.of(relativePath).getNameCount() - 1;
        }

        public String fileName() {
            Path fileName = Path.of(relativePath).getFileName();
            return fileName == null ? "" : fileName.toString();
        }
    }

    public record DirectoryListing(List<DirectoryEntry> entries, boolean truncated) {

        public DirectoryListing {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    private final class DirectoryCollector extends SimpleFileVisitor<Path> {

        private final Path projectRoot;
        private final Path listingRoot;
        private final List<DirectoryEntry> entries = new ArrayList<>();
        private boolean truncated;

        private DirectoryCollector(Path projectRoot, Path listingRoot) {
            this.projectRoot = projectRoot;
            this.listingRoot = listingRoot;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(directory)) {
                return directory.equals(listingRoot) ? failUnsafeRoot() : FileVisitResult.SKIP_SUBTREE;
            }
            PatchWorkspaceTarget target = revalidateVisitedPath(projectRoot, directory);
            if (target != null && !patchWorkspaceFileService.isDirectory(target)) {
                throw new PatchWorkspaceException("unsafe_target_path");
            }
            if (directory.equals(listingRoot)) {
                return FileVisitResult.CONTINUE;
            }
            if (shouldIgnore(directory)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            int depth = listingRoot.relativize(directory).getNameCount();
            if (depth > properties.getMaxDirectoryDepth()) {
                truncated = true;
                return FileVisitResult.SKIP_SUBTREE;
            }
            return addEntry(directory, true);
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
            if (file.equals(listingRoot)) {
                throw new PatchWorkspaceException("unsafe_target_path");
            }
            if (attributes.isSymbolicLink() || Files.isSymbolicLink(file) || shouldIgnore(file)) {
                return FileVisitResult.CONTINUE;
            }
            int depth = listingRoot.relativize(file).getNameCount();
            if (depth > properties.getMaxDirectoryDepth()) {
                truncated = true;
                return FileVisitResult.CONTINUE;
            }
            PatchWorkspaceTarget target = revalidateVisitedPath(projectRoot, file);
            if (target == null || !patchWorkspaceFileService.isRegularFile(target)) {
                return FileVisitResult.CONTINUE;
            }
            return addEntry(file, false);
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
            throw exception;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
            if (exception != null) {
                throw exception;
            }
            return FileVisitResult.CONTINUE;
        }

        private FileVisitResult addEntry(Path path, boolean directory) {
            if (entries.size() >= properties.getMaxDirectoryEntries()) {
                truncated = true;
                return FileVisitResult.TERMINATE;
            }
            String relativePath = listingRoot.relativize(path).toString().replace('\\', '/');
            entries.add(new DirectoryEntry(relativePath, directory));
            return FileVisitResult.CONTINUE;
        }

        private FileVisitResult failUnsafeRoot() throws PatchWorkspaceException {
            throw new PatchWorkspaceException("unsafe_project_root");
        }
    }
}
