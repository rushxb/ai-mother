package com.rush.rushaicodemother.mapper;

/** 无法安全进入 stable/canary 对照组的任务计数。 */
public record PromptCanaryAttributionExclusionRow(
        Long ambiguousTaskCount,
        Long invalidAttributionTaskCount
) {
}
