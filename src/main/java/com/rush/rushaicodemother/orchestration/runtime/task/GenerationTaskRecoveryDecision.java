package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.Objects;

/** 为不可恢复的过期任务选择的终端状态和持久原因。 */
public record GenerationTaskRecoveryDecision(GenerationTaskStatus status, String reason) {

    /** 创建生成任务恢复决策实例并完成必要的依赖和初始状态设置。 */
    public GenerationTaskRecoveryDecision {
        Objects.requireNonNull(status, "status");
        if (!status.isTerminal()) {
            throw new IllegalArgumentException("recovery status must be terminal");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("recovery reason cannot be blank");
        }
        reason = reason.trim();
    }
}
