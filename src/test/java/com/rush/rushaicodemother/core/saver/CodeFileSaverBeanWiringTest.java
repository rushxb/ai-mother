package com.rush.rushaicodemother.core.saver;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceExecutionScope;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationCatalog;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CodeFileSaverBeanWiringTest {

    @Test
    void wiresAllGeneratedCodeSaversThroughWorkspaceBoundaries() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CodeStorageProperties.class,
                    WorkspaceFileSystemProperties.class,
                    GenerationWorkspaceExecutionScope.class,
                    GenerationWorkspacePublicationCatalog.class,
                    GenerationWorkspaceService.class,
                    WorkspaceFileSystemService.class,
                    GeneratedWorkspaceTrustPolicy.class,
                    HtmlCodeFileSaverTemplate.class,
                    MultiFileCodeFileSaverTemplate.class,
                    CodeFileSaverExecutor.class
            );

            context.refresh();

            assertNotNull(context.getBean(CodeFileSaverExecutor.class));
            assertEquals(2, context.getBeansOfType(CodeFileSaverTemplate.class).size());
        }
    }
}
