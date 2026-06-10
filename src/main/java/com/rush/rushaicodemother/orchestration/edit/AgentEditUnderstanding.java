package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

public record AgentEditUnderstanding(
        String structureSummary,
        List<String> affectedFiles,
        List<String> protectedFiles,
        List<String> impactedModules,
        List<String> referencedBy,
        List<String> symbols,
        List<String> diagnostics,
        String riskLevel
) {
}
