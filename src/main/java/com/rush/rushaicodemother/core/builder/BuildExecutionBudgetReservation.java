package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;

import java.util.Objects;

/** 一轮项目质量门禁共享的幂等构建预算预留。 */
public final class BuildExecutionBudgetReservation {

    private final GenerationExecutionContextService executionContextService;
    private final String taskId;
    private boolean reserved;

    private BuildExecutionBudgetReservation(
            GenerationExecutionContextService executionContextService,
            String taskId
    ) {
        this.executionContextService = Objects.requireNonNull(
                executionContextService,
                "生成执行上下文服务不能为空"
        );
        this.taskId = taskId;
    }

    public static BuildExecutionBudgetReservation forTask(
            GenerationExecutionContextService executionContextService,
            String taskId
    ) {
        return new BuildExecutionBudgetReservation(executionContextService, taskId);
    }

    /** 首个真实构建命令执行前扣减预算；同一门禁轮次的后续组件不会重复扣减。 */
    public synchronized void reserve() {
        if (reserved) {
            return;
        }
        executionContextService.consumeIfPresent(taskId, GenerationBudgetKind.BUILD_EXECUTION);
        reserved = true;
    }

    public synchronized boolean isReserved() {
        return reserved;
    }
}
