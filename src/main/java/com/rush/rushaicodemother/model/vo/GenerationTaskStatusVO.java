package com.rush.rushaicodemother.model.vo;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSnapshot;

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
        GenerationTaskProgressVO progress
) {
    public static GenerationTaskStatusVO from(GenerationTaskSnapshot snapshot) {
        return new GenerationTaskStatusVO(
                snapshot.taskId(), snapshot.appId(), snapshot.route(), snapshot.status(),
                snapshot.stage(), snapshot.stageMessage(), snapshot.submittedAt(), snapshot.deadlineAt(),
                snapshot.cancellationRequested(), snapshot.cancellationReason(),
                stringifyKeys(snapshot.usages()), stringifyKeys(snapshot.limits()),
                GenerationTaskProgressVO.from(snapshot.progress()));
    }

    private static Map<String, Integer> stringifyKeys(Map<GenerationBudgetKind, Integer> source) {
        Map<String, Integer> target = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((kind, value) -> target.put(kind.name().toLowerCase(), value));
        }
        return Map.copyOf(target);
    }
}
