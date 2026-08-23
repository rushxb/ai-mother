package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.bootstrap.FullStackGenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static com.rush.rushaicodemother.orchestration.GenerationOrchestrationTestFixture.frozenRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FullStackGenerationTemplateBootstrapAdapterTest {

    @Test
    void fullStackAdapterMustCombineBothTemplatesAndPortContext() {
        VueProjectTemplateBootstrapService vueService =
                mock(VueProjectTemplateBootstrapService.class);
        BackendProjectTemplateBootstrapService backendService =
                mock(BackendProjectTemplateBootstrapService.class);
        FullStackPortAllocator portAllocator = mock(FullStackPortAllocator.class);
        GenerationWorkspace workspace = workspace();
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(9L, CodeGenTypeEnum.FULL_STACK_PROJECT))
                .thenReturn(workspace);
        when(portAllocator.allocate(workspace)).thenReturn(fullStackContext());
        when(vueService.bootstrapIfNecessary(
                9L, CodeGenTypeEnum.FULL_STACK_PROJECT, "创建全栈商品管理"))
                .thenReturn(VueProjectTemplateBootstrapService.BootstrapResult.created(
                        "vue-web-admin", "frontend", 12));
        when(backendService.bootstrapIfNecessary(
                9L, CodeGenTypeEnum.FULL_STACK_PROJECT))
                .thenReturn(BackendProjectTemplateBootstrapService.BootstrapResult.created(
                        "go-sqlite-backend-basic", "backend", 8));
        TemplateAgentNode node = new TemplateAgentNode(
                new GenerationTemplateBootstrapRegistry(
                        workspaceService,
                        List.of(new FullStackGenerationTemplateBootstrapAdapter(
                                vueService, backendService, portAllocator))
                ));

        AgentNodeResult result = node.execute(context());

        assertEquals("vue-web-admin+go-sqlite-backend-basic", result.data().get("templateId"));
        assertEquals(20, result.data().get("fileCount"));
        assertEquals(5173, result.data().get("frontendPort"));
        assertEquals(8081, result.data().get("backendPort"));
        assertTrue(result.artifacts().stream().anyMatch(
                artifact -> "template_bootstrap".equals(artifact.key())));
        assertTrue(result.artifacts().stream().anyMatch(
                artifact -> "full_stack_context".equals(artifact.key())));
        verify(portAllocator).allocate(workspace);
    }

    private GenerationAgentContext context() {
        App app = new App();
        app.setId(9L);
        app.setCodeGenType(CodeGenTypeEnum.FULL_STACK_PROJECT.getValue());
        GenerationOrchestrationRequest request = frozenRequest(
                app, "创建全栈商品管理", CodeGenTypeEnum.FULL_STACK_PROJECT, "create", false);
        GenerationAgentContext context = new GenerationAgentContext(
                request, new GenerationOrchestrationTask(), true);
        context.setTargetType(CodeGenTypeEnum.FULL_STACK_PROJECT);
        return context;
    }

    private FullStackGenerationContext fullStackContext() {
        return new FullStackGenerationContext(
                9L,
                "workspace",
                "workspace/frontend",
                "workspace/backend",
                5173,
                8081,
                "http://127.0.0.1:5173",
                "http://127.0.0.1:8081",
                "/api",
                "VITE_API_BASE_URL",
                "http://127.0.0.1:8081/api",
                ":8081",
                "reserved"
        );
    }

    private GenerationWorkspace workspace() {
        Path root = Path.of("workspace").toAbsolutePath().normalize();
        return new GenerationWorkspace(
                9L,
                CodeGenTypeEnum.FULL_STACK_PROJECT,
                root,
                root,
                true,
                root.resolve("frontend"),
                root.resolve("backend"),
                Set.of(),
                Set.of("vue", "go")
        );
    }
}
