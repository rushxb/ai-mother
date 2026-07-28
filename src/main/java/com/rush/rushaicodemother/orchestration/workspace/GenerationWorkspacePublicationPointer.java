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

    /** 创建生成工作区发布{@code Pointer}实例并完成必要的依赖和初始状态设置。 */
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

    /**
 * 根据输入数据创建当前对象。
 *
 * @param appId 应用编号
 * @param codeGenType 代码生成类型
 * @param fence 围栏
 * @param publishedAt {@code publishedAt} 对应的调用参数
 * @return 生成工作区发布{@code Pointer}
 */
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

    /**
 * 返回{@code owned}按。
 *
 * @param fence 围栏
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean ownedBy(GenerationExecutionFence fence) {
        return fence != null
                && taskId.equals(fence.taskId())
                && executionEpoch == fence.executionEpoch();
    }
}
