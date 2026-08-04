package com.rush.rushaicodemother.orchestration.attempt.completion;

/** 单项完成证据；仅保存稳定类型、来源和中文摘要，不承载原始提示词或工具输出。 */
public record GenerationCompletionEvidence(
        GenerationCompletionEvidenceType type,
        String source,
        String summary
) {

    public GenerationCompletionEvidence {
        if (type == null) {
            throw new IllegalArgumentException("完成证据类型不能为空");
        }
        source = requireText(source, "完成证据来源不能为空");
        summary = requireText(summary, "完成证据摘要不能为空");
    }

    public static GenerationCompletionEvidence of(GenerationCompletionEvidenceType type,
                                                   String source,
                                                   String summary) {
        return new GenerationCompletionEvidence(type, source, summary);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
