package com.yupi.yuaicodemother.ai.tools;

import com.yupi.yuaicodemother.constant.AppConstant;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AI 工具路径辅助类
 */
final class ToolPathSupport {

    private ToolPathSupport() {
    }

    static Path resolveProjectRoot(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 无效，无法定位项目工作区");
        }
        String projectDirName = "vue_project_" + appId;
        return Paths.get(AppConstant.CODE_OUTPUT_ROOT_DIR, projectDirName)
                .toAbsolutePath()
                .normalize();
    }

    static Path resolvePath(String relativePath, Long appId) {
        Path projectRoot = resolveProjectRoot(appId);
        if (relativePath == null || relativePath.isBlank()) {
            return projectRoot;
        }
        Path inputPath = Paths.get(relativePath);
        Path resolvedPath = inputPath.isAbsolute() ? inputPath.normalize() : projectRoot.resolve(relativePath).normalize();
        ensureWithinProject(projectRoot, resolvedPath);
        return resolvedPath;
    }

    static void ensureWithinProject(Path projectRoot, Path targetPath) {
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalizedTarget = targetPath.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
    }
}
