package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;

/** 一次可幂等持久化的模型调用用量。 */
public record GenerationModelCallCommand(
        String callId,
        String taskId,
        Long appId,
        Long userId,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs,
        String finishReason,
        GenerationModelUsageSource usageSource
) {
}
