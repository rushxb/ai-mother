package com.rush.rushaicodemother.orchestration.template;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class TemplateBootstrapConsumerBeanWiringTest {

    @Test
    void shouldWireTemplateBoundaryAndConsumersWithoutCycles() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CodeStorageProperties.class,
                    WorkspaceFileSystemProperties.class,
                    TemplateMaterializationProperties.class,
                    WorkspaceFileSystemService.class,
                    GenerationWorkspaceService.class,
                    ProjectTemplateCatalog.class,
                    ProjectTemplateMaterializer.class,
                    TemplatePreWarmService.class,
                    ProjectTemplateBootstrapper.class,
                    VueProjectTemplateBootstrapService.class,
                    BackendProjectTemplateBootstrapService.class
            );

            context.refresh();

            assertNotNull(context.getBean(ProjectTemplateCatalog.class));
            assertNotNull(context.getBean(ProjectTemplateMaterializer.class));
            assertNotNull(context.getBean(TemplatePreWarmService.class));
            assertNotNull(context.getBean(ProjectTemplateBootstrapper.class));
            assertNotNull(context.getBean(VueProjectTemplateBootstrapService.class));
            assertNotNull(context.getBean(BackendProjectTemplateBootstrapService.class));
        }
    }
}