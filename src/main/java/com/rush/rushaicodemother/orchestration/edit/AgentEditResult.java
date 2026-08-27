package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;

import java.util.List;

/**
 * 智能体编辑执行结果。
 */
public record AgentEditResult(
        String taskId,
        String route,
        String summary,
        List<String> changedFiles,
        String status,
        int repairRounds,
        GenerationCompletionEvidenceSet completionEvidence
) {
    public AgentEditResult {
        taskId = taskId == null ? "" : taskId;
        route = route == null ? "" : route;
        summary = summary == null ? "" : summary;
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        status = status == null ? "" : status;
        repairRounds = Math.max(0, repairRounds);
        completionEvidence = completionEvidence == null
                ? GenerationCompletionEvidenceSet.empty()
                : completionEvidence;
    }

    /** 兼容失败结果及尚未迁移的调用方；未提供的验证事实必须保持为空。 */
    public AgentEditResult(String taskId,
                           String route,
                           String summary,
                           List<String> changedFiles,
                           String status,
                           int repairRounds) {
        this(taskId, route, summary, changedFiles, status, repairRounds,
                GenerationCompletionEvidenceSet.empty());
    }
}
