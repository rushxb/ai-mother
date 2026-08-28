package com.rush.rushaicodemother.orchestration.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 面向用户的低敏变更摘要，不包含文件路径或模型原文。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationChangeSummary(
        Integer changedFileCount,
        String summary
) {

    public GenerationChangeSummary {
        if (changedFileCount != null && changedFileCount < 0) {
            throw new IllegalArgumentException("变更文件数不能为负数");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("变更摘要不能为空");
        }
        summary = summary.trim();
    }
}
