package com.rush.rushaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 项目工作区辅助类
 */
public final class ProjectWorkspaceSupport {

    public static final Set<String> DEFAULT_IGNORED_NAMES = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "build", "target", "coverage", ".ai-code-index"
    );

    private ProjectWorkspaceSupport() {
    }

    public static List<Path> listProjectFiles(Path root) throws IOException {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(ProjectWorkspaceSupport::shouldInclude)
                    .map(root::relativize)
                    .sorted()
                    .toList();
        }
    }

    static void copyProject(Path sourceRoot, Path targetRoot) throws IOException {
        Files.createDirectories(targetRoot);
        try (Stream<Path> stream = Files.walk(sourceRoot)) {
            List<Path> sourcePaths = stream
                    .filter(ProjectWorkspaceSupport::shouldInclude)
                    .sorted()
                    .toList();
            for (Path sourcePath : sourcePaths) {
                Path relative = sourceRoot.relativize(sourcePath);
                Path targetPath = targetRoot.resolve(relative);
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.createDirectories(targetPath.getParent());
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    static void cleanDirectory(Path root) throws IOException {
        if (!Files.exists(root) || !Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> stream = Files.list(root)) {
            List<Path> children = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path child : children) {
                ensureChildOf(root, child);
                FileUtil.del(child.toFile());
            }
        }
    }

    static void ensureChildOf(Path root, Path child) {
        Path normalizedRoot = root.normalize();
        Path normalizedChild = child.normalize();
        if (!normalizedChild.startsWith(normalizedRoot)) {
            throw new IllegalArgumentException("非法路径，超出当前项目目录范围");
        }
    }

    private static boolean shouldInclude(Path path) {
        for (Path part : path.normalize()) {
            if (DEFAULT_IGNORED_NAMES.contains(part.toString())) {
                return false;
            }
        }
        return true;
    }
}
