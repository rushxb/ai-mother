package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceExecutionScope;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationCatalog;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class ToolPathSupportBeanWiringTest {

    @Test
    void shouldWireToolPathSupportThroughCanonicalWorkspaceService() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                     CodeStorageProperties.class,
                     GenerationToolExecutionContextService.class,
                     GenerationWorkspaceExecutionScope.class,
                     GenerationWorkspacePublicationCatalog.class,
                     GenerationWorkspaceService.class,
                    ToolPathSupport.class
            );

            context.refresh();

            assertNotNull(context.getBean(GenerationWorkspaceService.class));
            assertNotNull(context.getBean(ToolPathSupport.class));
        }
    }
}
