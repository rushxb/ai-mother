package com.rush.rushaicodemother.orchestration.patch;

import com.rush.rushaicodemother.config.PatchExecutionProperties;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.mockito.Mockito.mock;

class GenerationPatchApplyServiceSpringWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(GenerationTaskFenceGuard.class, () -> mock(GenerationTaskFenceGuard.class))
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
    @Import({
            // 补丁资源上限已下沉为常量，这里按普通组件导入即可。
            PatchExecutionProperties.class,
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
