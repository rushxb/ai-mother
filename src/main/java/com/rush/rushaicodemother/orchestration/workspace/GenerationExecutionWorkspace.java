package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一个由持久代执行纪元拥有的可写工作区。
 *
 * <p>工作空间路径永远不是用户可见的应用程序路径。它可能只会变得可见
 * 生成并验证完成后通过围栏发布服务。</p>
 */
public record GenerationExecutionWorkspace(
        Long appId,
        GenerationExecutionFence fence,
        CodeGenTypeEnum codeGenType,
        Path epochRootPath,
        Path typeRootPath,
        GenerationWorkspace workspace,
        Long seededFromEpoch
) {

    public GenerationExecutionWorkspace {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(codeGenType, "codeGenType");
        epochRootPath = requireAbsolute(epochRootPath, "epochRootPath");
        typeRootPath = requireAbsolute(typeRootPath, "typeRootPath");
        Objects.requireNonNull(workspace, "workspace");
        if (!Objects.equals(appId, workspace.appId()) || workspace.codeGenType() != codeGenType) {
            throw new IllegalArgumentException("execution workspace identity mismatch");
        }
        if (!workspace.canonicalRootPath().startsWith(typeRootPath)
                || !typeRootPath.startsWith(epochRootPath)) {
            throw new IllegalArgumentException("execution workspace path layout is invalid");
        }
        if (seededFromEpoch != null
                && (seededFromEpoch <= 0 || seededFromEpoch >= fence.executionEpoch())) {
            throw new IllegalArgumentException("seededFromEpoch must precede the current epoch");
        }
    }

    public String taskId() {
        return fence.taskId();
    }

    public long executionEpoch() {
        return fence.executionEpoch();
    }

    private static Path requireAbsolute(Path path, String field) {
        Path normalized = Objects.requireNonNull(path, field).toAbsolutePath().normalize();
        if (!normalized.isAbsolute()) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return normalized;
    }
}
