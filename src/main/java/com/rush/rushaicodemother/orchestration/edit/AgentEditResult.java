package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

public record AgentEditResult(
        String taskId,
        String route,
        String summary,
        List<String> changedFiles,
        String status,
        int repairRounds
) {
}
