package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContext;

import java.time.Instant;

/** 继续模型工具循环而不重放管道所需的版本化状态。 */
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
    /** v4 开始由执行快照持久化 Agent 根尝试和模型回合账本。 */
    public static final int CURRENT_SCHEMA_VERSION = 4;
    public static final int MIN_SUPPORTED_SCHEMA_VERSION = 1;

    /** 创建生成工具{@code Continuation}状态实例并完成必要的依赖和初始状态设置。 */
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

    /**
 * 返回{@code supports}结构版本。
 *
 * @param schemaVersion 结构版本
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public static boolean supportsSchemaVersion(Integer schemaVersion) {
        return schemaVersion != null
                && schemaVersion >= MIN_SUPPORTED_SCHEMA_VERSION
                && schemaVersion <= CURRENT_SCHEMA_VERSION;
    }
}
