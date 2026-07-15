package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.controller.StaticResourceController;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeploymentArtifactResourceServiceBeanWiringTest {

    @Test
    void shouldWireDeploymentResourceBeanGraphWithConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CodeStorageProperties.class,
                    DeploymentKeyPolicy.class,
                    SecurePathResolver.class,
                    DeploymentArtifactResourceService.class,
                    StaticResourceController.class
            );

            context.refresh();

            assertNotNull(context.getBean(CodeStorageProperties.class));
            assertNotNull(context.getBean(DeploymentArtifactResourceService.class));
            assertNotNull(context.getBean(StaticResourceController.class));
        }
    }
}
