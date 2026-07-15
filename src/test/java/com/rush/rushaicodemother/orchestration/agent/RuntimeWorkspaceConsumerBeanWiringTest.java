package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipeLibrary;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkillLibrary;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationContextCompressionService;
import com.rush.rushaicodemother.service.devserver.DevServerProjectLocator;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class RuntimeWorkspaceConsumerBeanWiringTest {

    @Test
    void wiresDevServerLocatorThroughCanonicalWorkspaceServices() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CodeStorageProperties.class,
                    WorkspaceFileSystemProperties.class,
                    GenerationWorkspaceService.class,
                    WorkspaceFileSystemService.class,
                    DevServerProjectLocator.class
            );

            context.refresh();

            assertNotNull(context.getBean(DevServerProjectLocator.class));
        }
    }

    @Test
    void wiresGenerationAgentSupportThroughCanonicalWorkspaceService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(GenerationAgentConfiguration.class);
            context.registerBean(GenerationRecipeLibrary.class, () -> mock(GenerationRecipeLibrary.class));
            context.registerBean(GenerationSkillLibrary.class, () -> mock(GenerationSkillLibrary.class));
            context.registerBean(WorkspaceSemanticIndexService.class, () -> mock(WorkspaceSemanticIndexService.class));
            context.registerBean(
                    GenerationContextCompressionService.class,
                    () -> mock(GenerationContextCompressionService.class)
            );
            context.registerBean(GenerationWorkspaceService.class, () -> mock(GenerationWorkspaceService.class));

            context.refresh();

            assertNotNull(context.getBean(GenerationAgentSupport.class));
        }
    }
}
