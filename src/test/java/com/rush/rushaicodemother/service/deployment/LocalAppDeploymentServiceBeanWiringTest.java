package com.rush.rushaicodemother.service.deployment;

import com.rush.rushaicodemother.config.CodeDeploymentProperties;
import com.rush.rushaicodemother.config.ScreenshotConfiguration;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.service.artifact.AppArtifactLifecycleService;
import com.rush.rushaicodemother.service.artifact.DeploymentKeyPolicy;
import com.rush.rushaicodemother.service.lifecycle.AppOperationLockManager;
import com.rush.rushaicodemother.service.screenshot.ScreenshotService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class LocalAppDeploymentServiceBeanWiringTest {

    @Test
    void shouldBeConstructedBySpringUsingExplicitDependencies() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AppArtifactLifecycleService.class,
                    () -> mock(AppArtifactLifecycleService.class));
            context.registerBean(VueProjectBuilder.class, () -> mock(VueProjectBuilder.class));
            context.registerBean(ScreenshotService.class, () -> mock(ScreenshotService.class));
            context.registerBean(AppMapper.class, () -> mock(AppMapper.class));
            context.registerBean(CodeDeploymentProperties.class, this::deploymentProperties);
            context.registerBean(AppOperationLockManager.class, AppOperationLockManager::new);
            context.registerBean(DeploymentKeyPolicy.class);
            context.registerBean(DeploymentKeyGenerator.class, () -> () -> "FixedKey1234");
            context.registerBean(
                    ScreenshotConfiguration.SCREENSHOT_TASK_EXECUTOR,
                    Executor.class,
                    () -> Runnable::run
            );
            context.registerBean(LocalAppDeploymentService.class);

            context.refresh();

            assertNotNull(context.getBean(LocalAppDeploymentService.class));
        }
    }

    private CodeDeploymentProperties deploymentProperties() {
        CodeDeploymentProperties properties = new CodeDeploymentProperties();
        properties.setDeployHost("http://localhost:91");
        return properties;
    }
}
