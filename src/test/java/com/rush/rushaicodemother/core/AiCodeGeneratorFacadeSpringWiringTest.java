package com.rush.rushaicodemother.core;

import com.rush.rushaicodemother.ai.AiCodeGeneratorServiceFactory;
import com.rush.rushaicodemother.ai.generation.LightweightCodeGenerationExecutor;
import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.core.parser.CodeParserExecutor;
import com.rush.rushaicodemother.core.saver.CodeFileSaverExecutor;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelTimeoutPolicy;
import com.rush.rushaicodemother.orchestration.runtime.model.RootModelRetryExecutor;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AiCodeGeneratorFacadeSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AiCodeGeneratorServiceFactory.class,
                    () -> mock(AiCodeGeneratorServiceFactory.class))
            .withBean(CodeFileSaverExecutor.class,
                    () -> mock(CodeFileSaverExecutor.class))
            .withBean(LightweightCodeGenerationExecutor.class,
                    () -> mock(LightweightCodeGenerationExecutor.class))
            .withBean(CodeParserExecutor.class,
                    () -> mock(CodeParserExecutor.class))
            .withBean(GenerationWorkspaceService.class,
                    () -> mock(GenerationWorkspaceService.class))
            .withBean(GenerationPerformanceMonitorService.class,
                    GenerationPerformanceMonitorService::new)
            .withBean(AiModelRuntimeProperties.class, AiModelRuntimeProperties::new)
            .withBean(AiModelMetricsCollector.class,
                    () -> new AiModelMetricsCollector(new SimpleMeterRegistry()))
            .withBean(GenerationStageAdmissionService.class,
                    () -> mock(GenerationStageAdmissionService.class))
            .withBean(GenerationAgentRuntime.class,
                    () -> mock(GenerationAgentRuntime.class))
            .withUserConfiguration(
                    RootModelRetryExecutor.class,
                    GenerationModelTimeoutPolicy.class,
                    GenerationModelInvocationCancellationBridge.class,
                    AiCodeGeneratorFacade.class
            );

    @Test
    void shouldSelectProductionConstructorsWithoutRequiringDefaultConstructors() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RootModelRetryExecutor.class);
            assertThat(context).hasSingleBean(GenerationModelTimeoutPolicy.class);
            assertThat(context).hasSingleBean(GenerationModelInvocationCancellationBridge.class);
            assertThat(context).hasSingleBean(AiCodeGeneratorFacade.class);
        });
    }
}
