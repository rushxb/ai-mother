package com.rush.rushaicodemother.orchestration.fullstack;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FullStackPortAllocatorTest {

    @Test
    void shouldAllocateAgainstCanonicalGenerationWorkspace() {
        Path root = Path.of("target", "test-workspaces", "full-stack-port-allocator", "full_stack_project_41")
                .toAbsolutePath()
                .normalize();
        GenerationWorkspace workspace = workspace(root, CodeGenTypeEnum.FULL_STACK_PROJECT);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(41L, CodeGenTypeEnum.FULL_STACK_PROJECT)).thenReturn(workspace);
        FullStackPortAllocator allocator = new FullStackPortAllocator(workspaceService);

        FullStackGenerationContext context = allocator.allocate(41L);
        FullStackGenerationContext repeatedContext = allocator.allocate(41L);

        assertEquals(context, repeatedContext);
        assertEquals(portable(root), context.workspaceRoot());
        assertEquals(portable(root.resolve("frontend")), context.frontendPath());
        assertEquals(portable(root.resolve("backend")), context.backendPath());
        assertTrue(context.frontendPort() >= 17000 && context.frontendPort() <= 17999);
        assertTrue(context.backendPort() >= 18000 && context.backendPort() <= 18999);
        verify(workspaceService, org.mockito.Mockito.times(2))
                .resolve(41L, CodeGenTypeEnum.FULL_STACK_PROJECT);
    }

    @Test
    void shouldRejectNonFullStackWorkspaceBeforePortAllocation() {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        FullStackPortAllocator allocator = new FullStackPortAllocator(workspaceService);
        Path root = Path.of("target", "test-workspaces", "full-stack-port-allocator", "vue_project_41")
                .toAbsolutePath()
                .normalize();

        assertThrows(BusinessException.class,
                () -> allocator.allocate(workspace(root, CodeGenTypeEnum.VUE_PROJECT)));
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum codeGenType) {
        Path frontendRoot = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT ? root.resolve("frontend") : root;
        Path backendRoot = codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT ? root.resolve("backend") : null;
        return new GenerationWorkspace(
                41L,
                codeGenType,
                root,
                root,
                false,
                frontendRoot,
                backendRoot,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }

    private String portable(Path path) {
        return path.toString().replace('\\', '/');
    }
}
