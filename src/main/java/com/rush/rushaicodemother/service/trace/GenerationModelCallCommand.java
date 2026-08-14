package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.model.enums.GenerationModelCallStatus;
import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.ModelInvocationBillingMode;
import com.rush.rushaicodemother.model.enums.ModelInvocationPurpose;

/** 一次可幂等持久化的模型调用用量。 */
public record GenerationModelCallCommand(
        String callId,
        String taskId,
        Long appId,
        Long userId,
        ModelInvocationPurpose invocationPurpose,
        ModelInvocationBillingMode billingMode,
        String billingExemptionReason,
        String provider,
        String model,
        GenerationModelCallStatus status,
        String providerRequestId,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long latencyMs,
        String finishReason,
        GenerationModelUsageSource usageSource,
        String errorCategory,
        GenerationModelCallProvenance provenance
) {
    /** 兼容生成链路调用方；外围调用必须显式传入 purpose 与豁免。 */
    public GenerationModelCallCommand(
            String callId,
            String taskId,
            Long appId,
            Long userId,
            String provider,
            String model,
            GenerationModelCallStatus status,
            String providerRequestId,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long latencyMs,
            String finishReason,
            GenerationModelUsageSource usageSource,
            String errorCategory,
            GenerationModelCallProvenance provenance
    ) {
        this(callId, taskId, appId, userId,
                ModelInvocationPurpose.GENERATION, ModelInvocationBillingMode.BILLABLE, null,
                provider, model, status, providerRequestId, promptTokens, completionTokens,
                totalTokens, latencyMs, finishReason, usageSource, errorCategory, provenance);
    }
}
