package com.rush.rushaicodemother.service.impl;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 检查生成项目工作区是否具备继续构建或自动修复的基本条件。
 */
public final class GeneratedProjectWorkspaceInspector {

    private static final Set<String> IGNORED_DIR_NAMES = Set.of(
            ".git", ".idea", "node_modules", "dist", "target"
    );

    private static final Set<String> IGNORED_FILE_NAMES = Set.of(
            ".ds_store", ".ai-code-install.stamp", ".ai-code-critical.stamp", ".ai-code-presentation.stamp"
    );

    private static final Set<String> KEY_PROJECT_FILES = Set.of(
            "package.json",
            "index.html",
            "vite.config.js",
            "vite.config.ts",
            "vite.config.mjs",
            "src/main.js",
            "src/main.ts",
            "src/app.vue"
    );

    private GeneratedProjectWorkspaceInspector() {
    }

    public static WorkspaceState inspectVueProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        return inspectVueProject(Path.of(projectPath));
    }

    public static WorkspaceState inspectVueProject(Path projectPath) {
        if (projectPath == null) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        Path rootPath = projectPath.toAbsolutePath().normalize();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return new WorkspaceState(rootPath, false, 0, 0, Set.of());
        }
        long[] fileCount = {0};
        long[] meaningfulFileCount = {0};
        Set<String> detectedKeyFiles = new LinkedHashSet<>();
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!rootPath.equals(dir) && IGNORED_DIR_NAMES.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (!attrs.isRegularFile()) {
                        return FileVisitResult.CONTINUE;
                    }
                    fileCount[0]++;
                    String relativePath = normalizeRelativePath(rootPath, file);
                    if (shouldIgnoreFile(relativePath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    meaningfulFileCount[0]++;
                    String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
                    if (KEY_PROJECT_FILES.contains(normalizedPath)) {
                        detectedKeyFiles.add(relativePath);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            return new WorkspaceState(rootPath, true, fileCount[0], meaningfulFileCount[0], detectedKeyFiles);
        }
        return new WorkspaceState(rootPath, true, fileCount[0], meaningfulFileCount[0], detectedKeyFiles);
    }

    private static boolean shouldIgnoreFile(String relativePath) {
        String normalized = relativePath.replace("\\", "/").toLowerCase(Locale.ROOT);
        return IGNORED_FILE_NAMES.contains(fileName(normalized));
    }

    private static String fileName(String normalizedPath) {
        int index = normalizedPath.lastIndexOf('/');
        return index >= 0 ? normalizedPath.substring(index + 1) : normalizedPath;
    }

    private static String normalizeRelativePath(Path rootPath, Path filePath) {
        return rootPath.relativize(filePath).toString().replace("\\", "/");
    }

    public record WorkspaceState(Path rootPath,
                                 boolean directoryExists,
                                 long fileCount,
                                 long meaningfulFileCount,
                                 Set<String> detectedKeyFiles) {

        public boolean hasAnyGeneratedFiles() {
            return meaningfulFileCount > 0;
        }

        public boolean canAutoRepair() {
            return directoryExists && (hasKeyProjectFiles() || meaningfulFileCount >= 2);
        }

        public boolean hasKeyProjectFiles() {
            return detectedKeyFiles != null && !detectedKeyFiles.isEmpty();
        }

        public String missingProjectSummary() {
            if (!directoryExists) {
                return "代码生成未产出项目目录，无法执行构建或自动修复";
            }
            return "代码生成未产出有效项目文件，无法执行构建或自动修复";
        }
    }
}
