package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;

import java.util.List;

/** 只读分析模型的最小输入契约，不暴露任何工作区写能力。 */
public record ReadOnlyAnalysisRequest(
        IntentOperationType operationType,
        String userPrompt,
        String projectContext,
        List<String> allowedReferences
) {

    public ReadOnlyAnalysisRequest {
        if (operationType == null) {
            throw new IllegalArgumentException("只读分析操作类型不能为空");
        }
        userPrompt = requireText(userPrompt, "只读分析需求不能为空");
        projectContext = requireText(projectContext, "只读分析项目上下文不能为空");
        allowedReferences = allowedReferences == null ? List.of() : List.copyOf(allowedReferences);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
