package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/**
 * 智能体编辑理解结果的不可变数据载体。
 */
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
