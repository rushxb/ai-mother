package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 启动一次显式智能体根尝试所需的不可变输入。 */
public record GenerationAgentExecutionRequest(
        Long appId,
        String userPrompt,
        CodeGenTypeEnum codeGenType,
        GenerationPerformanceProfile performanceProfile,
        String projectPath,
        GenerationExecutionContext executionContext,
        BooleanSupplier cancelChecker,
        Consumer<GenerationCancellationHandle> cancellationHandleConsumer
) {
    public GenerationAgentExecutionRequest {
        if (appId == null || appId <= 0L) {
            throw new IllegalArgumentException("应用 ID 必须大于 0");
        }
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalArgumentException("智能体用户提示不能为空");
        }
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT
                && codeGenType != CodeGenTypeEnum.BACKEND_PROJECT
                && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new IllegalArgumentException("显式智能体运行时仅支持工程项目生成");
        }
        if (projectPath == null || projectPath.isBlank()) {
            throw new IllegalArgumentException("智能体工程路径不能为空");
        }
        Objects.requireNonNull(executionContext, "生成执行上下文不能为空");
        if (!Objects.equals(appId, executionContext.appId())) {
            throw new IllegalArgumentException("智能体请求与执行上下文的应用不一致");
        }
        if (executionContext.executionFence() == null) {
            throw new IllegalArgumentException("智能体请求缺少执行栅栏");
        }
        cancelChecker = cancelChecker == null ? () -> false : cancelChecker;
        cancellationHandleConsumer = cancellationHandleConsumer == null
                ? ignored -> { }
                : cancellationHandleConsumer;
    }
}
