package com.rush.rushaicodemother.orchestration.finalization;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;

/** 生成任务终态收口所需的最小强类型命令。 */
public record GenerationFinalizationCommand(
        String taskId,
        Long appId,
        GenerationExecutionFence executionFence,
        GenerationTaskStatus status,
        String reason,
        String memorySummary,
        GenerationOutcomeQuality outcomeQuality,
        @JsonInclude(JsonInclude.Include.NON_NULL) GenerationDeliveryReceipt deliveryReceipt
) {

    public GenerationFinalizationCommand {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("生成任务 ID 不能为空");
        }
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 不合法");
        }
        if (status == null || !status.isTerminal()) {
            throw new IllegalArgumentException("生成任务状态必须是终态");
        }
        if (executionFence != null && !taskId.equals(executionFence.taskId())) {
            throw new IllegalArgumentException("生成任务 ID 与执行围栏不一致");
        }
    }

    /** 兼容旧终态命令构造；新执行链应显式携带交付回执。 */
    public GenerationFinalizationCommand(
            String taskId,
            Long appId,
            GenerationExecutionFence executionFence,
            GenerationTaskStatus status,
            String reason,
            String memorySummary,
            GenerationOutcomeQuality outcomeQuality) {
        this(taskId, appId, executionFence, status, reason, memorySummary, outcomeQuality, null);
    }

    public static GenerationFinalizationCommand of(
            String taskId,
            Long appId,
            GenerationExecutionFence executionFence,
            GenerationTaskStatus status,
            String reason,
            String memorySummary,
            GenerationOutcomeQuality outcomeQuality) {
        return new GenerationFinalizationCommand(
                taskId, appId, executionFence, status, reason, memorySummary, outcomeQuality, null);
    }

    public static GenerationFinalizationCommand of(
            String taskId,
            Long appId,
            GenerationExecutionFence executionFence,
            GenerationTaskStatus status,
            String reason,
            String memorySummary,
            GenerationOutcomeQuality outcomeQuality,
            GenerationDeliveryReceipt deliveryReceipt) {
        return new GenerationFinalizationCommand(
                taskId, appId, executionFence, status, reason, memorySummary,
                outcomeQuality, deliveryReceipt);
    }
}
