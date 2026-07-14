package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.service.dependency.ProjectDependencyInstaller;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class VueProjectBuilderSpringContextTest {

    @Test
    void shouldInstantiateBuilderModulesWithConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ProjectCommandProperties.class, ProjectCommandProperties::new);
            context.registerBean(WorkspaceFileSystemProperties.class, WorkspaceFileSystemProperties::new);
            context.registerBean(ProjectDependencyInstaller.class, () -> mock(ProjectDependencyInstaller.class));
            context.registerBean(
                    GenerationPerformanceMonitorService.class,
                    () -> mock(GenerationPerformanceMonitorService.class)
            );
            context.registerBean(ProjectCommandExecutor.class, () -> mock(ProjectCommandExecutor.class));
            context.registerBean(
                    GenerationExecutionContextService.class,
                    () -> mock(GenerationExecutionContextService.class)
            );
            context.register(
                    VueBuildStateStore.class,
                    VueProjectSnapshotService.class,
                    VueProjectScriptResolver.class,
                    VueBuildResultRegistry.class,
                    VueBuildCommandService.class,
                    VueProjectBuilder.class
            );

            context.refresh();

            assertNotNull(context.getBean(VueProjectBuilder.class));
            assertNotNull(context.getBean(VueBuildCommandService.class));
            assertNotNull(context.getBean(VueBuildStateStore.class));
            assertNotNull(context.getBean(VueProjectSnapshotService.class));
            assertNotNull(context.getBean(VueProjectScriptResolver.class));
            assertNotNull(context.getBean(VueBuildResultRegistry.class));
        }
    }
}
