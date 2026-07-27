package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

import java.time.Instant;
import java.util.Objects;

/** 以原子方式将指针切换到一个完全准备好的已发布工作区版本。 */
public record GenerationWorkspacePublicationPointer(
        int schemaVersion,
        Long appId,
        CodeGenTypeEnum codeGenType,
        String taskId,
        long executionEpoch,
        Instant publishedAt
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public GenerationWorkspacePublicationPointer {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("publication pointer schema version is unsupported");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("appId must be positive");
        }
        Objects.requireNonNull(codeGenType, "codeGenType");
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
        if (executionEpoch <= 0) {
            throw new IllegalArgumentException("executionEpoch must be positive");
        }
        Objects.requireNonNull(publishedAt, "publishedAt");
    }

    public static GenerationWorkspacePublicationPointer from(
            Long appId,
            CodeGenTypeEnum codeGenType,
            GenerationExecutionFence fence,
            Instant publishedAt
    ) {
        Objects.requireNonNull(fence, "fence");
        return new GenerationWorkspacePublicationPointer(
                CURRENT_SCHEMA_VERSION,
                appId,
                codeGenType,
                fence.taskId(),
                fence.executionEpoch(),
                publishedAt
        );
    }

    public boolean ownedBy(GenerationExecutionFence fence) {
        return fence != null
                && taskId.equals(fence.taskId())
                && executionEpoch == fence.executionEpoch();
    }
}
