package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationValidationSummary;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** 公共任务状态视图，包括阶段、预计到达时间和任务范围的预算使用情况。 */
public record GenerationTaskStatusVO(
        String taskId,
        Long appId,
        String route,
        String status,
        String stage,
        String stageMessage,
        Instant submittedAt,
        Instant deadlineAt,
        boolean cancellationRequested,
        String cancellationReason,
        Map<String, Integer> usages,
        Map<String, Integer> limits,
        GenerationTaskProgressVO progress,
        String failureCategory,
        Boolean retryable,
        String recoveryAction,
        GenerationValidationSummary validationSummary,
        GenerationDeliveryReceipt deliveryReceipt,
        GenerationCostSummary costSummary,
        GenerationTaskGuidanceVO guidance,
        int contractVersion
) {
    /** 可行动指引从版本 3 起可用；旧客户端可继续忽略未知 JSON 字段。 */
    public static final int CURRENT_CONTRACT_VERSION = 3;

    /**
 * 根据输入数据创建当前对象。
 *
 * @param snapshot 快照
 * @return 生成任务状态视图对象
 */
    public static GenerationTaskStatusVO from(GenerationTaskSnapshot snapshot) {
        GenerationDeliveryReceipt receipt = snapshot.deliveryReceipt();
        return new GenerationTaskStatusVO(
                snapshot.taskId(), snapshot.appId(), snapshot.route(), snapshot.status(),
                snapshot.stage(), snapshot.stageMessage(), snapshot.submittedAt(), snapshot.deadlineAt(),
                snapshot.cancellationRequested(), snapshot.cancellationReason(),
                stringifyKeys(snapshot.usages()), stringifyKeys(snapshot.limits()),
                GenerationTaskProgressVO.from(snapshot.progress()),
                receipt == null ? null : receipt.failureCategory(),
                receipt == null ? null : receipt.retryable(),
                receipt == null ? null : receipt.recoveryAction(),
                receipt == null ? null : receipt.validationSummary(),
                receipt,
                receipt == null ? null : receipt.costSummary(),
                GenerationTaskGuidanceVO.from(snapshot),
                CURRENT_CONTRACT_VERSION);
    }

    private static Map<String, Integer> stringifyKeys(Map<GenerationBudgetKind, Integer> source) {
        Map<String, Integer> target = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((kind, value) -> target.put(kind.name().toLowerCase(), value));
        }
        return Map.copyOf(target);
    }
}
