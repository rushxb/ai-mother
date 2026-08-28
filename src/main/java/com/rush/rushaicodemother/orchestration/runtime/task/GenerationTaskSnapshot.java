package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;

import java.time.Instant;
import java.util.Map;

/** 通过应用程序和 HTTP 查询接缝公开的不可变任务状态视图。 */
public record GenerationTaskSnapshot(
        String taskId,
        Long appId,
        Long userId,
        String route,
        String status,
        String stage,
        String stageMessage,
        Instant submittedAt,
        Instant deadlineAt,
        boolean cancellationRequested,
        String cancellationReason,
        Map<GenerationBudgetKind, Integer> usages,
        Map<GenerationBudgetKind, Integer> limits,
        GenerationTaskProgressEstimate progress,
        GenerationDeliveryReceipt deliveryReceipt
) {

    /** 兼容运行中任务与旧测试构造；终态数据库快照应显式携带交付回执。 */
    public GenerationTaskSnapshot(
            String taskId,
            Long appId,
            Long userId,
            String route,
            String status,
            String stage,
            String stageMessage,
            Instant submittedAt,
            Instant deadlineAt,
            boolean cancellationRequested,
            String cancellationReason,
            Map<GenerationBudgetKind, Integer> usages,
            Map<GenerationBudgetKind, Integer> limits,
            GenerationTaskProgressEstimate progress) {
        this(taskId, appId, userId, route, status, stage, stageMessage,
                submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                usages, limits, progress, null);
    }
}
