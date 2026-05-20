package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWorkspaceServiceTest {

    private final GenerationWorkspaceService service = new GenerationWorkspaceService();

    @Test
    void shouldResolveVueWorkspaceUnderCodeOutputRoot() {
        App app = new App();
        app.setId(123L);

        GenerationWorkspace workspace = service.resolve(app, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(123L, workspace.appId());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, workspace.codeGenType());
        assertTrue(workspace.canonicalRootPath().endsWith(Path.of("vue_project_123")));
        assertTrue(workspace.canonicalRootPath().startsWith(Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize()));
        assertEquals(workspace.canonicalRootPath(), workspace.frontendRootPath());
        assertNull(workspace.backendRootPath());
        assertFalse(workspace.exists());
        assertTrue(workspace.hiddenFileNames().contains("node_modules"));
        assertTrue(workspace.editableExtensions().contains("vue"));
    }

    @Test
    void shouldResolveFullStackSubDirectories() {
        App app = new App();
        app.setId(456L);

        GenerationWorkspace workspace = service.resolve(app, CodeGenTypeEnum.FULL_STACK_PROJECT);

        assertTrue(workspace.canonicalRootPath().endsWith(Path.of("full_stack_project_456")));
        assertNotNull(workspace.frontendRootPath());
        assertNotNull(workspace.backendRootPath());
        assertTrue(workspace.frontendRootPath().endsWith(Path.of("full_stack_project_456", "frontend")));
        assertTrue(workspace.backendRootPath().endsWith(Path.of("full_stack_project_456", "backend")));
    }
}
