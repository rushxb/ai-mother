package com.rush.rushaicodemother.orchestration.workspace;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 生成项目工作区的不可变检查事实。
 *
 * <p>该值对象位于 workspace 领域，不暴露具体检查器实现；关键文件集合在构造时复制，
 * 防止调用方修改已经用于构建与自动修复决策的事实。</p>
 */
public record GeneratedProjectWorkspaceInspection(
        Path rootPath,
        boolean directoryExists,
        long fileCount,
        long meaningfulFileCount,
        Set<String> detectedKeyFiles
) {

    public GeneratedProjectWorkspaceInspection {
        rootPath = Objects.requireNonNull(rootPath, "工作区根路径不能为空")
                .toAbsolutePath()
                .normalize();
        if (fileCount < 0 || meaningfulFileCount < 0 || meaningfulFileCount > fileCount) {
            throw new IllegalArgumentException("工作区文件计数不合法");
        }
        detectedKeyFiles = detectedKeyFiles == null || detectedKeyFiles.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(detectedKeyFiles));
    }

    public boolean hasAnyGeneratedFiles() {
        return meaningfulFileCount > 0;
    }

    public boolean canAutoRepair() {
        return directoryExists && (hasKeyProjectFiles() || meaningfulFileCount >= 2);
    }

    public boolean hasKeyProjectFiles() {
        return !detectedKeyFiles.isEmpty();
    }

    /** 返回无法继续构建或自动修复时可展示给用户的稳定摘要。 */
    public String missingProjectSummary() {
        if (!directoryExists) {
            return "代码生成未产出项目目录，无法执行构建或自动修复";
        }
        return "代码生成未产出有效项目文件，无法执行构建或自动修复";
    }
}
