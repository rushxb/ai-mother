package com.rush.rushaicodemother.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.generation.HtmlLightweightCodeGenerationAdapter;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationExecutor;
import com.rush.rushaicodemother.ai.generation.MultiFileLightweightCodeGenerationAdapter;
import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.ai.provenance.AiModelProvenanceFactory;
import com.rush.rushaicodemother.ai.model.capacity.AiModelCapacityGuard;
import com.rush.rushaicodemother.ai.model.failover.FirstTokenHedgeScheduler;
import com.rush.rushaicodemother.ai.model.transport.CancellableAiStreamingRequestExecutor;
import com.rush.rushaicodemother.ai.model.transport.AiModelOutboundHttpClientFactory;
import com.rush.rushaicodemother.ai.tools.ToolManager;
import com.rush.rushaicodemother.config.AiModelCircuitBreakerProperties;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.AiModelMonitorListener;
import com.rush.rushaicodemother.monitor.AiModelTimeoutMonitor;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.ConservativeModelTokenUsageEstimator;
import com.rush.rushaicodemother.infrastructure.security.AiModelOutboundDestinationPolicy;
import com.rush.rushaicodemother.infrastructure.security.SystemHostAddressResolver;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationAgentCompletionPolicy;
import com.rush.rushaicodemother.orchestration.context.AgentConversationFolder;
import com.rush.rushaicodemother.orchestration.context.AgentConversationTokenAccountant;
import com.rush.rushaicodemother.orchestration.context.AgentConversationWindowPolicy;
import com.rush.rushaicodemother.orchestration.context.AiContextPackBudgetProperties;
import com.rush.rushaicodemother.orchestration.context.OpenAiCompatibleContextTokenEstimator;
import com.rush.rushaicodemother.orchestration.context.ToolRoundPathExtractor;
import com.rush.rushaicodemother.orchestration.runtime.agent.DefaultGenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentConversationInitializer;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentPromptResolver;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationStreamingModelCallSupervisor;
import com.rush.rushaicodemother.orchestration.runtime.model.ManagedGenerationModelTimeoutScheduler;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.tool.AiToolInvocationPolicy;
import com.rush.rushaicodemother.orchestration.tool.CompletedToolCallContextCompactor;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentTurnPolicy;
import com.rush.rushaicodemother.orchestration.tool.DurableToolConversationCodec;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutionPlanner;
import com.rush.rushaicodemother.orchestration.tool.ToolBatchExecutor;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalService;
import com.rush.rushaicodemother.orchestration.tool.ToolExecutionFailurePolicy;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.aimodel.AiModelCircuitBreaker;
import com.rush.rushaicodemother.service.aimodel.AiModelRuntimeService;
import com.rush.rushaicodemother.service.aimodel.AiModelSecretService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ProductionAiWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AiModelRuntimeProperties.class, AiModelRuntimeProperties::new)
            .withBean(GenerationRuntimeProperties.class, GenerationRuntimeProperties::new)
            .withBean(AiModelMetricsCollector.class,
                    () -> new AiModelMetricsCollector(new SimpleMeterRegistry()))
            .withBean(GenerationPerformanceMonitorService.class,
                    GenerationPerformanceMonitorService::new)
            .withBean(GenerationTraceService.class,
                    () -> mock(GenerationTraceService.class))
            .withBean(AiModelProvenanceFactory.class,
                    () -> mock(AiModelProvenanceFactory.class))
            .withBean(AiModelRuntimeService.class,
                    () -> mock(AiModelRuntimeService.class))
            .withBean(OpenAiThinkingPolicy.class,
                    () -> mock(OpenAiThinkingPolicy.class))
            .withBean(AiModelCapacityGuard.class,
                    () -> mock(AiModelCapacityGuard.class))
            .withBean(AiModelSecretService.class,
                    () -> mock(AiModelSecretService.class))
            .withBean(FirstTokenHedgeScheduler.class,
                    () -> mock(FirstTokenHedgeScheduler.class))
            .withBean(ChatMemoryStore.class,
                    () -> mock(ChatMemoryStore.class))
            .withBean(ChatHistoryService.class,
                    () -> mock(ChatHistoryService.class))
            .withBean(ToolManager.class,
                    () -> mock(ToolManager.class))
            .withBean(ToolExecutionFailurePolicy.class,
                    () -> mock(ToolExecutionFailurePolicy.class))
            .withBean(AiToolInvocationPolicy.class,
                    () -> mock(AiToolInvocationPolicy.class))
            .withBean(PromptSystemMessageTransformer.class,
                    () -> mock(PromptSystemMessageTransformer.class))
            .withBean(CompletedToolCallContextCompactor.class,
                    () -> mock(CompletedToolCallContextCompactor.class))
            .withBean(ToolApprovalService.class,
                    () -> mock(ToolApprovalService.class))
            .withBean(GenerationStageAdmissionService.class,
                    () -> mock(GenerationStageAdmissionService.class))
            // 对话折叠依赖真实 JSON 解析器提取工具参数中的文件路径，不能用 mock 替代。
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withUserConfiguration(
                    AiModelCircuitBreakerProperties.class,
                    AiModelCircuitBreaker.class,
                    GenerationExecutionContextService.class,
                    GenerationAgentCompletionPolicy.class,
                    GenerationAgentTurnPolicy.class,
                    GenerationToolExecutionContextService.class,
                    DurableToolConversationCodec.class,
                    GenerationAgentPromptResolver.class,
                    AiContextPackBudgetProperties.class,
                    OpenAiCompatibleContextTokenEstimator.class,
                    ToolRoundPathExtractor.class,
                    AgentConversationFolder.class,
                    AgentConversationTokenAccountant.class,
                    AgentConversationWindowPolicy.class,
                    GenerationAgentConversationInitializer.class,
                    ToolBatchExecutionPlanner.class,
                    ToolBatchExecutor.class,
                    DefaultGenerationAgentRuntime.class,
                    AiModelMonitorListener.class,
                    ConservativeModelTokenUsageEstimator.class,
                    GenerationModelInvocationCancellationBridge.class,
                    GenerationModelTimeoutPolicy.class,
                    CancellableAiStreamingRequestExecutor.class,
                    SystemHostAddressResolver.class,
                    AiModelOutboundDestinationPolicy.class,
                    AiModelOutboundHttpClientFactory.class,
                    ManagedGenerationModelTimeoutScheduler.class,
                    AiModelTimeoutMonitor.class,
                    AiStreamingCallRuntime.class,
                    StreamingModelFactory.class,
                    HtmlLightweightCodeGenerationAdapter.class,
                    MultiFileLightweightCodeGenerationAdapter.class,
                    LightweightCodeGenerationExecutor.class,
                    AiCodeGeneratorServiceFactory.class,
                    GenerationStreamingModelCallSupervisor.class
            );

    @Test
    void shouldCreateProductionAiChainUsingExplicitConstructors() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AiModelCircuitBreaker.class);
            assertThat(context).hasSingleBean(AiModelMonitorListener.class);
            assertThat(context).hasSingleBean(StreamingModelFactory.class);
            assertThat(context).hasSingleBean(LightweightCodeGenerationExecutor.class);
            assertThat(context).hasSingleBean(AiCodeGeneratorServiceFactory.class);
            assertThat(context).hasSingleBean(CancellableAiStreamingRequestExecutor.class);
            assertThat(context).hasSingleBean(AiModelOutboundHttpClientFactory.class);
            assertThat(context).hasSingleBean(GenerationStreamingModelCallSupervisor.class);
            assertThat(context).hasSingleBean(GenerationAgentRuntime.class);
            assertThat(context.getBean(GenerationAgentRuntime.class))
                    .isInstanceOf(DefaultGenerationAgentRuntime.class);
        });
    }
}
