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

    private static final Set<String> VUE_KEY_PROJECT_FILES = Set.of(
            "package.json",
            "index.html",
            "vite.config.js",
            "vite.config.ts",
            "vite.config.mjs",
            "src/main.js",
            "src/main.ts",
            "src/app.vue"
    );

    private static final Set<String> BACKEND_KEY_PROJECT_FILES = Set.of(
            "go.mod",
            "go.sum",
            "cmd/server/main.go"
    );

    private static final Set<String> FULL_STACK_KEY_PROJECT_FILES = Set.of(
            "frontend/package.json",
            "frontend/src/main.js",
            "frontend/src/main.ts",
            "frontend/src/app.vue",
            "backend/go.mod",
            "backend/go.sum",
            "backend/cmd/server/main.go"
    );

    private GeneratedProjectWorkspaceInspector() {
    }

    /**
 * 返回{@code inspect}{@code Vue}项目。
 *
 * @param projectPath 项目路径
 * @return {@code Generated}项目工作区{@code Inspector}
 */
    public static WorkspaceState inspectVueProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        return inspectVueProject(Path.of(projectPath));
    }

    public static WorkspaceState inspectVueProject(Path projectPath) {
        return inspectProject(projectPath, VUE_KEY_PROJECT_FILES);
    }

    public static WorkspaceState inspectBackendProject(Path projectPath) {
        return inspectProject(projectPath, BACKEND_KEY_PROJECT_FILES);
    }

    public static WorkspaceState inspectFullStackProject(Path projectPath) {
        return inspectProject(projectPath, FULL_STACK_KEY_PROJECT_FILES);
    }

    /** 返回{@code inspect}项目。 */
    private static WorkspaceState inspectProject(Path projectPath, Set<String> keyProjectFiles) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
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
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                /**
 * 在访问目录内容前执行安全校验和资源边界判断。
 *
 * @param dir {@code dir} 对应的调用参数
 * @param attrs 待处理的 {@code attrs} 集合
 * @return 方法执行结果
 */
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!rootPath.equals(dir) && IGNORED_DIR_NAMES.contains(dir.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                /**
 * 返回访问文件。
 *
 * @param file 文件
 * @param attrs 待处理的 {@code attrs} 集合
 * @return {@code Generated}项目工作区{@code Inspector}
 */
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
                    if (keyProjectFiles.contains(normalizedPath)) {
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

        /**
 * 返回{@code missing}项目汇总。
 *
 * @return 处理后的工作区状态文本
 */
        public String missingProjectSummary() {
            if (!directoryExists) {
                return "代码生成未产出项目目录，无法执行构建或自动修复";
            }
            return "代码生成未产出有效项目文件，无法执行构建或自动修复";
        }
    }
}
