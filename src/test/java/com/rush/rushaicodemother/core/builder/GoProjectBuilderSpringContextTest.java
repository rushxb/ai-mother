package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.process.GoProjectCommandExecutor;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.heavy.BackendProjectValidationAdapter;
import com.rush.rushaicodemother.orchestration.heavy.FullStackProjectValidationAdapter;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectBuildValidationAdapter;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectBuildValidationService;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectRuntimeValidationAdapter;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectRuntimeValidationService;
import com.rush.rushaicodemother.orchestration.heavy.VueProjectValidationAdapter;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class GoProjectBuilderSpringContextTest {

    @Test
    void shouldInstantiateGoAndCompositeBuildGateWithConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    GoProjectCommandExecutor.class,
                    () -> mock(GoProjectCommandExecutor.class)
            );
            context.registerBean(
                    GenerationPerformanceMonitorService.class,
                    () -> mock(GenerationPerformanceMonitorService.class)
            );
            context.registerBean(
                    GenerationExecutionContextService.class,
                    () -> mock(GenerationExecutionContextService.class)
            );
            context.registerBean(VueProjectBuilder.class, () -> mock(VueProjectBuilder.class));
            context.registerBean(
                    DevServerValidationService.class,
                    () -> mock(DevServerValidationService.class));
            context.registerBean(
                    GeneratedBackendRuntimeVerifier.class,
                    () -> mock(GeneratedBackendRuntimeVerifier.class));
            context.registerBean(
                    GeneratedFullStackRuntimeVerifier.class,
                    () -> mock(GeneratedFullStackRuntimeVerifier.class));
            context.register(
                    ProjectCommandProperties.class,
                    WorkspaceFileSystemProperties.class,
                    GoProjectSnapshotService.class,
                    GoBuildResultRegistry.class,
                    GoBuildCommandService.class,
                    GoProjectBuilder.class,
                    VueProjectValidationAdapter.class,
                    BackendProjectValidationAdapter.class,
                    FullStackProjectValidationAdapter.class,
                    GenerationProjectBuildValidationService.class,
                    GenerationProjectRuntimeValidationService.class);

            context.refresh();

            assertNotNull(context.getBean(GoBuildCommandService.class));
            assertNotNull(context.getBean(GoProjectSnapshotService.class));
            assertNotNull(context.getBean(GoBuildResultRegistry.class));
            assertNotNull(context.getBean(GoProjectBuilder.class));
            assertNotNull(context.getBean(GenerationProjectBuildValidationService.class));
            assertNotNull(context.getBean(GenerationProjectRuntimeValidationService.class));
            assertEquals(3, context.getBeansOfType(
                    GenerationProjectBuildValidationAdapter.class).size());
            assertEquals(3, context.getBeansOfType(
                    GenerationProjectRuntimeValidationAdapter.class).size());
        }
    }
}
