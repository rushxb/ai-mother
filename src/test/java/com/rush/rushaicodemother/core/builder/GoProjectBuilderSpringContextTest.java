package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.process.GoProjectCommandExecutor;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.heavy.GenerationProjectBuildValidationService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

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
            context.register(
                    ProjectCommandProperties.class,
                    WorkspaceFileSystemProperties.class,
                    GoProjectSnapshotService.class,
                    GoBuildResultRegistry.class,
                    GoBuildCommandService.class,
                    GoProjectBuilder.class,
                    GenerationProjectBuildValidationService.class);

            context.refresh();

            assertNotNull(context.getBean(GoBuildCommandService.class));
            assertNotNull(context.getBean(GoProjectSnapshotService.class));
            assertNotNull(context.getBean(GoBuildResultRegistry.class));
            assertNotNull(context.getBean(GoProjectBuilder.class));
            assertNotNull(context.getBean(GenerationProjectBuildValidationService.class));
        }
    }
}
