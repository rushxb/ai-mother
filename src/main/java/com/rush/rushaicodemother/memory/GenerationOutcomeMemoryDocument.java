package com.rush.rushaicodemother.memory;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.Map;

/** 将直接写与 outbox 写统一为同一份可检索文档。 */
record GenerationOutcomeMemoryDocument(
        MemoryType type,
        String content,
        Map<String, Object> metadata
) {
    private static final int MAX_USER_PROMPT_CODE_POINTS = 3_000;
    private static final int MAX_SUMMARY_CODE_POINTS = 4_500;

    static GenerationOutcomeMemoryDocument from(GenerationOutcomeMemoryRequest request,
                                                String source) {
        GenerationTaskStatus status = request.status();
        MemoryType type = status == GenerationTaskStatus.SUCCESS
                ? MemoryType.TASK_OUTCOME
                : MemoryType.FAILURE_LESSON;
        String content = "用户需求：" + compact(request.userPrompt(), MAX_USER_PROMPT_CODE_POINTS, "未提供")
                + "\n执行结果：" + compact(request.memorySummary(), MAX_SUMMARY_CODE_POINTS, "未提供");
        return new GenerationOutcomeMemoryDocument(
                type,
                content,
                Map.of(
                        "source", label(source),
                        "taskStatus", status == null ? "unknown" : status.getValue(),
                        "orchestrationMode", label(request.orchestrationMode()),
                        "targetType", label(request.targetCodeGenType())
                )
        );
    }

    private static String compact(String value, int maxCodePoints, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= maxCodePoints) {
            return normalized;
        }
        int end = normalized.offsetByCodePoints(0, maxCodePoints);
        return normalized.substring(0, end) + "...";
    }

    private static String label(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
