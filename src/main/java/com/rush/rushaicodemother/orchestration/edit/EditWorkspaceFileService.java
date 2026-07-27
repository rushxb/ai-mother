package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 集中编辑工作流程的路径验证、有界遍历和有界 UTF-8 读取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EditWorkspaceFileService {

    private static final Set<String> INDEXABLE_EXTENSIONS = Set.of(
            "vue", "js", "ts", "jsx", "tsx", "css", "scss", "less",
            "html", "json", "go", "sql", "md", "yml", "yaml"
    );

    private final EditLocatorProperties properties;

    public Optional<EditWorkspaceFile> resolveEditableFile(GenerationWorkspace workspace, String relativePath) {
        return resolveRegularFile(workspace, relativePath)
                .filter(file -> isEditablePath(workspace, file.relativePath()));
    }

    public Optional<EditWorkspaceFile> resolveIndexableFile(GenerationWorkspace workspace, String relativePath) {
        return resolveRegularFile(workspace, relativePath)
                .filter(file -> isIndexablePath(workspace, file.relativePath()));
    }

    public List<EditWorkspaceFile> scanIndexableFiles(GenerationWorkspace workspace, String relativeDirectory) {
        return scanFiles(workspace, relativeDirectory, true);
    }

    public List<EditWorkspaceFile> scanEditableFiles(GenerationWorkspace workspace, String relativeDirectory) {
        return scanFiles(workspace, relativeDirectory, false);
    }

    public Optional<String> readUtf8(GenerationWorkspace workspace, EditWorkspaceFile file) {
        if (file == null) {
            return Optional.empty();
        }
        Optional<EditWorkspaceFile> currentFile = resolveEditableFile(workspace, file.relativePath());
        if (currentFile.isEmpty() || !currentFile.get().absolutePath().equals(file.absolutePath())) {
            return Optional.empty();
        }
        try {
            Path path = currentFile.get().absolutePath();
            Set<java.nio.file.OpenOption> openOptions = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
            try (SeekableByteChannel channel = Files.newByteChannel(path, openOptions)) {
                long size = channel.size();
                if (size > properties.getMaxReadableFileBytes() || size > Integer.MAX_VALUE) {
                    log.debug("Skip oversized edit context file: {}, bytes: {}", file.relativePath(), size);
                    return Optional.empty();
                }
                ByteBuffer buffer = ByteBuffer.allocate((int) size);
                while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                    // 通读已经打开的、无后续的文件句柄。
                }
                ByteBuffer overflowProbe = ByteBuffer.allocate(1);
                if (channel.read(overflowProbe) >= 0) {
                    log.debug("Skip edit context file that grew while being read: {}", file.relativePath());
                    return Optional.empty();
                }
                buffer.flip();
                return Optional.of(StandardCharsets.UTF_8.decode(buffer).toString());
            }
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            log.debug("Failed to read edit workspace file: {}", file.relativePath(), LogExceptionSanitizer.sanitize(e));
            return Optional.empty();
        }
    }

    public boolean isIndexablePath(GenerationWorkspace workspace, String relativePath) {
        if (!isEditablePath(workspace, relativePath)) {
            return false;
        }
        String extension = FileUtil.extName(relativePath).toLowerCase(Locale.ROOT);
        return INDEXABLE_EXTENSIONS.contains(extension);
    }

    private List<EditWorkspaceFile> scanFiles(GenerationWorkspace workspace,
                                              String relativeDirectory,
                                              boolean indexableOnly) {
        Optional<Path> root = workspaceRoot(workspace);
        Optional<Path> startDirectory = resolveDirectory(workspace, relativeDirectory);
        if (root.isEmpty() || startDirectory.isEmpty()) {
            return List.of();
        }

        List<EditWorkspaceFile> files = new ArrayList<>();
        int[] scannedFileCount = {0};
        try {
            Path realRoot = root.get().toRealPath();
            Files.walkFileTree(startDirectory.get(), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(startDirectory.get()) && isHiddenPath(root.get(), directory, workspace)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    try {
                        if (Files.isSymbolicLink(directory) || !directory.toRealPath().startsWith(realRoot)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                    } catch (IOException | SecurityException e) {
                        log.debug("Skip unsafe edit workspace directory: {}", directory, LogExceptionSanitizer.sanitize(e));
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) {
                    scannedFileCount[0]++;
                    if (scannedFileCount[0] > properties.getMaxScannedFiles()) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(path)) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (!path.toRealPath().startsWith(realRoot)) {
                            return FileVisitResult.CONTINUE;
                        }
                        String relativePath = normalizeRelativePath(root.get().relativize(path));
                        boolean accepted = indexableOnly
                                ? isIndexablePath(workspace, relativePath)
                                : isEditablePath(workspace, relativePath);
                        if (accepted) {
                            files.add(new EditWorkspaceFile(relativePath, path.toAbsolutePath().normalize()));
                        }
                    } catch (IOException | SecurityException e) {
                        log.debug("Skip unsafe edit workspace file: {}", path, LogExceptionSanitizer.sanitize(e));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    log.debug("Skip unreadable edit workspace path: {}", file, LogExceptionSanitizer.sanitize(exception));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | SecurityException e) {
            log.debug("Failed to scan edit workspace directory: {}", startDirectory.get(), LogExceptionSanitizer.sanitize(e));
            return List.of();
        }
        files.sort(Comparator.comparing(EditWorkspaceFile::relativePath));
        return List.copyOf(files);
    }

    private Optional<EditWorkspaceFile> resolveRegularFile(GenerationWorkspace workspace, String relativePath) {
        Optional<Path> root = workspaceRoot(workspace);
        Optional<Path> resolved = resolvePath(root.orElse(null), relativePath, workspace);
        if (root.isEmpty() || resolved.isEmpty()) {
            return Optional.empty();
        }
        Path path = resolved.get();
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                return Optional.empty();
            }
            Path realRoot = root.get().toRealPath();
            Path realPath = path.toRealPath();
            if (!realPath.startsWith(realRoot)) {
                return Optional.empty();
            }
            String normalizedRelativePath = normalizeRelativePath(root.get().relativize(path));
            return Optional.of(new EditWorkspaceFile(normalizedRelativePath, path.toAbsolutePath().normalize()));
        } catch (IOException | SecurityException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> resolveDirectory(GenerationWorkspace workspace, String relativeDirectory) {
        Optional<Path> root = workspaceRoot(workspace);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        if (StrUtil.isBlank(relativeDirectory)) {
            return root;
        }
        Optional<Path> resolved = resolvePath(root.get(), relativeDirectory, workspace);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Path directory = resolved.get();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(directory)) {
            return Optional.empty();
        }
        return Optional.of(directory);
    }

    private Optional<Path> resolvePath(Path root, String relativePath, GenerationWorkspace workspace) {
        if (root == null || StrUtil.isBlank(relativePath) || relativePath.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        try {
            Path suppliedPath = Path.of(relativePath.trim().replace('\\', '/'));
            if (suppliedPath.isAbsolute()) {
                return Optional.empty();
            }
            for (Path segment : suppliedPath) {
                String name = segment.toString();
                if ("..".equals(name) || isHiddenName(workspace, name)) {
                    return Optional.empty();
                }
            }
            Path normalizedPath = root.resolve(suppliedPath).normalize();
            if (!normalizedPath.startsWith(root)) {
                return Optional.empty();
            }
            Path current = root;
            for (Path segment : root.relativize(normalizedPath)) {
                current = current.resolve(segment);
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(current)) {
                    return Optional.empty();
                }
            }
            return Optional.of(normalizedPath);
        } catch (InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    private Optional<Path> workspaceRoot(GenerationWorkspace workspace) {
        if (workspace == null || !workspace.exists() || workspace.canonicalRootPath() == null) {
            return Optional.empty();
        }
        Path root = workspace.canonicalRootPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(root)) {
            return Optional.empty();
        }
        return Optional.of(root);
    }

    private boolean isEditablePath(GenerationWorkspace workspace, String relativePath) {
        if (workspace == null || StrUtil.isBlank(relativePath)) {
            return false;
        }
        String normalizedPath = relativePath.replace('\\', '/');
        for (String part : normalizedPath.split("/")) {
            if (StrUtil.isBlank(part) || "..".equals(part) || isHiddenName(workspace, part)) {
                return false;
            }
        }
        String extension = FileUtil.extName(normalizedPath).toLowerCase(Locale.ROOT);
        Set<String> editableExtensions = workspace.editableExtensions();
        return editableExtensions != null && editableExtensions.contains(extension);
    }

    private boolean isHiddenPath(Path root, Path path, GenerationWorkspace workspace) {
        Path relativePath = root.relativize(path);
        for (Path segment : relativePath) {
            if (isHiddenName(workspace, segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean isHiddenName(GenerationWorkspace workspace, String name) {
        if (StrUtil.isBlank(name) || name.startsWith(".")) {
            return true;
        }
        Set<String> hiddenNames = workspace == null ? Set.of() : workspace.hiddenFileNames();
        return hiddenNames != null && hiddenNames.stream().anyMatch(hiddenName -> hiddenName.equalsIgnoreCase(name));
    }

    private String normalizeRelativePath(Path relativePath) {
        return relativePath.toString().replace('\\', '/');
    }
}
