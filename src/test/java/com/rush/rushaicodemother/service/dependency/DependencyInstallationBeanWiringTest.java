package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.config.ExternalProcessProperties;
import com.rush.rushaicodemother.config.NodeToolchainProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.HostLocalGeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DependencyInstallationBeanWiringTest {

    @Test
    void shouldWireDependencyInstallationModuleWithProductionConstructors() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("app.generated-code-sandbox.mode=host-local").applyTo(context);
            context.registerBean(DependencyInstallProperties.class);
            context.registerBean(ExternalProcessProperties.class);
            context.registerBean(NodeToolchainProperties.class);
            context.registerBean(GenerationRuntimeProperties.class);
            context.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            context.registerBean(Tracer.class, () -> Tracer.NOOP);
            context.register(GenerationExecutionContextService.class);
            context.register(ProjectProcessTerminator.class);
            context.register(HostLocalGeneratedCodeProcessSandbox.class);
            context.register(GeneratedCodeSandboxMetricsCollector.class);
            context.register(ManagedProcessExecutor.class);
            context.register(NodeToolchain.class);
            context.register(NodeProjectDirectoryValidator.class);
            context.register(PnpmInstallCommandExecutor.class);
            context.register(NodeModulesIntegrityService.class);
            context.register(PnpmProjectDependencyInstaller.class);

            context.refresh();

            assertNotNull(context.getBean(ProjectDependencyInstaller.class));
            assertNotNull(context.getBean(PnpmInstallCommandExecutor.class));
            assertNotNull(context.getBean(NodeModulesIntegrityService.class));
        }
    }
}
