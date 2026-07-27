package com.rush.rushaicodemother.orchestration.edit;

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
        int repairRounds
) {
    public AgentEditResult {
        taskId = taskId == null ? "" : taskId;
        route = route == null ? "" : route;
        summary = summary == null ? "" : summary;
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        status = status == null ? "" : status;
        repairRounds = Math.max(0, repairRounds);
    }
}
