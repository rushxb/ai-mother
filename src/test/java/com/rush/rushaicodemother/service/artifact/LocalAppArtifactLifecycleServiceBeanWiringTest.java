package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class LocalAppArtifactLifecycleServiceBeanWiringTest {

    @Test
    void shouldWireTheProductionArtifactLifecycleBeanGraph() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ArtifactLifecycleProperties.class);
            context.registerBean(CodeStorageProperties.class);
            context.registerBean(DeploymentKeyPolicy.class);
            context.registerBean(ManagedProcessExecutor.class, () -> mock(ManagedProcessExecutor.class));
            context.register(
                    RobocopyDirectoryCopier.class,
                    ArtifactDirectoryCopier.class,
                    ArtifactPathMover.class,
                    LocalAppArtifactLifecycleService.class
            );

            context.refresh();

            assertNotNull(context.getBean(RobocopyDirectoryCopier.class));
            assertNotNull(context.getBean(ArtifactDirectoryCopier.class));
            assertNotNull(context.getBean(ArtifactPathMover.class));
            assertNotNull(context.getBean(AppArtifactLifecycleService.class));
            assertNotNull(context.getBean(LocalAppArtifactLifecycleService.class));
        }
    }
}