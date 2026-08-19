package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTemplateBootstrapRegistryTest {

    @Test
    void registeredAdapterMustBootstrapNewTypeThroughOneSharedModule() {
        Path projectRoot = Path.of("target/test-workspaces/template-bootstrap/html")
                .toAbsolutePath()
                .normalize();
        GenerationWorkspace workspace = workspace(projectRoot, CodeGenTypeEnum.HTML);
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(7L, CodeGenTypeEnum.HTML)).thenReturn(workspace);
        GenerationTemplateBootstrapAdapter htmlAdapter = new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public GenerationTemplateBootstrapOutput bootstrap(
                    GenerationTemplateBootstrapRequest request
            ) {
                assertEquals(7L, request.appId());
                assertEquals("创建静态活动页", request.userMessage());
                assertEquals(workspace, request.workspace());
                Map<String, Object> payload = Map.of(
                        "bootstrapped", true,
                        "templateId", "html-landing"
                );
                return new GenerationTemplateBootstrapOutput(
                        true,
                        "HTML 项目模板",
                        "已复制 HTML 项目模板：html-landing",
                        payload,
                        Map.of("frontend", payload),
                        Map.of()
                );
            }
        };
        GenerationTemplateBootstrapRegistry registry = new GenerationTemplateBootstrapRegistry(
                workspaceService,
                List.of(htmlAdapter)
        );

        GenerationTemplateBootstrapResult result = registry.bootstrap(
                7L,
                CodeGenTypeEnum.HTML,
                "创建静态活动页"
        );

        assertTrue(result.supported());
        assertTrue(result.successful());
        assertEquals(projectRoot, result.projectRoot());
        assertEquals("html-landing", result.templatePayload().get("templateId"));
        assertEquals(
                "html-landing",
                ((Map<?, ?>) result.runtimePayload().get("frontend")).get("templateId")
        );
        verify(workspaceService).resolve(7L, CodeGenTypeEnum.HTML);
    }

    @Test
    void workspaceTypeMismatchMustFailClosedBeforeAdapterExecution() {
        Path projectRoot = Path.of("target/test-workspaces/template-bootstrap/mismatch")
                .toAbsolutePath()
                .normalize();
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(7L, CodeGenTypeEnum.HTML))
                .thenReturn(workspace(projectRoot, CodeGenTypeEnum.VUE_PROJECT));
        GenerationTemplateBootstrapAdapter htmlAdapter = new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public GenerationTemplateBootstrapOutput bootstrap(
                    GenerationTemplateBootstrapRequest request
            ) {
                return new GenerationTemplateBootstrapOutput(
                        true,
                        "错误模板",
                        "不应执行",
                        Map.of("bootstrapped", true),
                        Map.of(),
                        Map.of()
                );
            }
        };
        GenerationTemplateBootstrapRegistry registry = new GenerationTemplateBootstrapRegistry(
                workspaceService,
                List.of(htmlAdapter)
        );

        assertThrows(
                IllegalStateException.class,
                () -> registry.bootstrap(7L, CodeGenTypeEnum.HTML, "创建静态活动页")
        );
    }

    private GenerationWorkspace workspace(Path root, CodeGenTypeEnum codeGenType) {
        return new GenerationWorkspace(
                7L,
                codeGenType,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of("html")
        );
    }
}
