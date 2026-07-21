package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;

import java.time.Instant;

/** Versioned state required to continue the model tool loop without replaying the pipeline. */
public record GenerationToolContinuationState(
        int schemaVersion,
        String taskId,
        Long appId,
        Long userId,
        String route,
        String userPrompt,
        CodeGenTypeEnum codeGenType,
        GenerationPerformanceProfile performanceProfile,
        GenerationPreparation preparation,
        GenerationExecutionLimits executionLimits,
        GenerationExecutionSnapshot execution,
        GenerationTraceContext traceContext,
        DurableToolConversation durableConversation,
        Instant capturedAt
) {
    public static final int CURRENT_SCHEMA_VERSION = 3;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    public GenerationToolContinuationState {
        if (!supportsSchemaVersion(schemaVersion)) {
            throw new IllegalArgumentException("unsupported tool continuation state schema");
        }
        if (traceContext == null) {
            traceContext = GenerationTraceContext.empty();
        }
        if (schemaVersion >= 3 && durableConversation == null) {
            throw new IllegalArgumentException("durable tool conversation is required");
        }
    }

    public GenerationToolContinuationState(int schemaVersion,
                                           String taskId,
                                           Long appId,
                                           Long userId,
                                           String route,
                                           String userPrompt,
                                           CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile performanceProfile,
                                           GenerationPreparation preparation,
                                           GenerationExecutionLimits executionLimits,
                                           GenerationExecutionSnapshot execution,
                                           GenerationTraceContext traceContext,
                                           Instant capturedAt) {
        this(schemaVersion, taskId, appId, userId, route, userPrompt, codeGenType,
                performanceProfile, preparation, executionLimits, execution,
                traceContext, null, capturedAt);
    }

    public GenerationToolContinuationState(int schemaVersion,
                                           String taskId,
                                           Long appId,
                                           Long userId,
                                           String route,
                                           String userPrompt,
                                           CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile performanceProfile,
                                           GenerationPreparation preparation,
                                           GenerationExecutionLimits executionLimits,
                                           GenerationExecutionSnapshot execution,
                                           Instant capturedAt) {
        this(schemaVersion, taskId, appId, userId, route, userPrompt, codeGenType,
                performanceProfile, preparation, executionLimits, execution,
                GenerationTraceContext.empty(), null, capturedAt);
    }

    public GenerationToolContinuationState(int schemaVersion,
                                           String taskId,
                                           Long appId,
                                           Long userId,
                                           String route,
                                           String userPrompt,
                                           CodeGenTypeEnum codeGenType,
                                           GenerationPerformanceProfile performanceProfile,
                                           GenerationPreparation preparation,
                                           GenerationExecutionLimits executionLimits,
                                           GenerationExecutionSnapshot execution,
                                           DurableToolConversation durableConversation,
                                           Instant capturedAt) {
        this(schemaVersion, taskId, appId, userId, route, userPrompt, codeGenType,
                performanceProfile, preparation, executionLimits, execution,
                GenerationTraceContext.empty(), durableConversation, capturedAt);
    }

    public static boolean supportsSchemaVersion(Integer schemaVersion) {
        return schemaVersion != null
                && schemaVersion >= MIN_SUPPORTED_SCHEMA_VERSION
                && schemaVersion <= CURRENT_SCHEMA_VERSION;
    }
}
