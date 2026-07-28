package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationState;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import reactor.core.publisher.Flux;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 主生成与人工审批恢复共同依赖的显式智能体运行时端口。 */
public interface GenerationAgentRuntime {

    Flux<GenerationStreamEvent> start(GenerationAgentExecutionRequest request);

    Flux<GenerationStreamEvent> continueAfterDecision(
            ToolApprovalRecord approval,
            GenerationToolContinuationState state,
            GenerationExecutionContext executionContext,
            BooleanSupplier cancelChecker,
            Consumer<GenerationCancellationHandle> cancellationHandleConsumer
    );
}
