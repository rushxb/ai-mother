package com.rush.rushaicodemother.orchestration.delivery;

import java.util.List;

/** 终态交付所观察到的验证结果；只公开稳定证据类型，不公开命令或日志。 */
public record GenerationValidationSummary(
        String status,
        String highestLevel,
        List<String> evidenceTypes,
        String summary
) {

    public GenerationValidationSummary {
        status = requireToken(status, "验证状态不能为空");
        highestLevel = requireToken(highestLevel, "验证级别不能为空");
        evidenceTypes = evidenceTypes == null ? List.of() : List.copyOf(evidenceTypes);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("验证摘要不能为空");
        }
        summary = summary.trim();
    }

    private static String requireToken(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
