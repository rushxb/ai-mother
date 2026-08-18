package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;

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

    /** 检查 Vue 工程目录。 */
    public static GeneratedProjectWorkspaceInspection inspectVueProject(String projectPath) {
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        return inspectVueProject(Path.of(projectPath));
    }

    public static GeneratedProjectWorkspaceInspection inspectVueProject(Path projectPath) {
        return inspectProject(projectPath, VUE_KEY_PROJECT_FILES);
    }

    public static GeneratedProjectWorkspaceInspection inspectBackendProject(Path projectPath) {
        return inspectProject(projectPath, BACKEND_KEY_PROJECT_FILES);
    }

    public static GeneratedProjectWorkspaceInspection inspectFullStackProject(Path projectPath) {
        return inspectProject(projectPath, FULL_STACK_KEY_PROJECT_FILES);
    }

    /** 按工程类型声明的关键文件检查项目目录。 */
    private static GeneratedProjectWorkspaceInspection inspectProject(
            Path projectPath,
            Set<String> keyProjectFiles
    ) {
        if (projectPath == null) {
            throw new IllegalArgumentException("projectPath 不能为空");
        }
        Path rootPath = projectPath.toAbsolutePath().normalize();
        if (!Files.exists(rootPath) || !Files.isDirectory(rootPath)) {
            return new GeneratedProjectWorkspaceInspection(
                    rootPath, false, 0, 0, Set.of());
        }
        long[] fileCount = {0};
        long[] meaningfulFileCount = {0};
        Set<String> detectedKeyFiles = new LinkedHashSet<>();
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
        } catch (IOException ignored) {
            return new GeneratedProjectWorkspaceInspection(
                    rootPath, true, fileCount[0], meaningfulFileCount[0], detectedKeyFiles);
        }
        return new GeneratedProjectWorkspaceInspection(
                rootPath, true, fileCount[0], meaningfulFileCount[0], detectedKeyFiles);
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

}
