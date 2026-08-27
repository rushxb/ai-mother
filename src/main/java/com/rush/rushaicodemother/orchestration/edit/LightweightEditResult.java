package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;

import java.util.List;

/**
 * 轻量编辑执行结果。
 */
public record LightweightEditResult(
        String taskId,
        String route,
        String summary,
        List<String> appliedOperations,
        String validationResult,
        GenerationCompletionEvidenceSet completionEvidence
) {
    public LightweightEditResult {
        taskId = taskId == null ? "" : taskId;
        route = route == null ? "" : route;
        summary = summary == null ? "" : summary;
        appliedOperations = appliedOperations == null ? List.of() : List.copyOf(appliedOperations);
        validationResult = validationResult == null ? "" : validationResult;
        completionEvidence = completionEvidence == null
                ? GenerationCompletionEvidenceSet.empty()
                : completionEvidence;
    }

    /** 兼容失败结果和旧调用方；没有 Validator 观测时完成证据必须为空。 */
    public LightweightEditResult(String taskId,
                                 String route,
                                 String summary,
                                 List<String> appliedOperations,
                                 String validationResult) {
        this(taskId, route, summary, appliedOperations, validationResult,
                GenerationCompletionEvidenceSet.empty());
    }
}
