package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class GenerationPatchApplyServiceSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(PatchServiceConfiguration.class);

    @Test
    void shouldCreatePatchServiceWithoutCircularDependencies() {
        contextRunner.run(context -> {
            context.getBean(GenerationPatchApplyService.class);
            context.getBean(PatchWorkspaceFileService.class);
            context.getBean(PatchOperationResourcePolicy.class);
            context.getBean(PatchBatchRollbackService.class);
            context.getBean(PatchOperationValidator.class);
            context.getBean(PatchOperationExecutor.class);
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(PatchExecutionProperties.class)
    @Import({
            GenerationPatchApplyService.class,
            PatchWorkspaceFileService.class,
            PatchOperationResourcePolicy.class,
            PatchBatchRollbackService.class,
            PatchStructuredContentService.class,
            FrontendPatchImportPolicy.class,
            PatchOperationValidator.class,
            PatchOperationExecutor.class,
            GenerationOrchestrationMetricsCollector.class
    })
    static class PatchServiceConfiguration {
    }
}
