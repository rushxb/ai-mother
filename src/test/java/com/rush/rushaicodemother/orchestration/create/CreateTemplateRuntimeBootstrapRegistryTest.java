package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapOutput;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CreateTemplateRuntimeBootstrapRegistryTest {

    @Test
    void createRuntimeMustConsumeTheSameRegisteredBootstrapModule() {
        Path projectRoot = Path.of("target/test-workspaces/create-template-runtime/html")
                .toAbsolutePath()
                .normalize();
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(1L, CodeGenTypeEnum.HTML))
                .thenReturn(workspace(projectRoot));
        GenerationTemplateBootstrapRegistry registry = new GenerationTemplateBootstrapRegistry(
                workspaceService,
                List.of(htmlAdapter())
        );
        CreateTemplateRuntime runtime = new CreateTemplateRuntime(
                registry,
                new CreatePatchMergeService(),
                mock(CreatePreWriteValidationService.class),
                null,
                null,
                mock(com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard.class),
                new LandingSlotFallbackRenderer()
        );

        SlotFillResult result = runtime.generate(app(), request(), plan());

        assertNotNull(result);
        Map<?, ?> bootstrap = (Map<?, ?>) result.metadata().get("bootstrap");
        assertEquals(
                "html-landing",
                ((Map<?, ?>) bootstrap.get("frontend")).get("templateId")
        );
    }

    private GenerationTemplateBootstrapAdapter htmlAdapter() {
        return new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public GenerationTemplateBootstrapOutput bootstrap(
                    GenerationTemplateBootstrapRequest request
            ) {
                Map<String, Object> payload = Map.of(
                        "bootstrapped", true,
                        "templateId", "html-landing"
                );
                return new GenerationTemplateBootstrapOutput(
                        true,
                        "HTML 项目模板",
                        "已复制 HTML 项目模板",
                        payload,
                        Map.of("frontend", payload),
                        Map.of()
                );
            }
        };
    }

    private GenerationWorkspace workspace(Path root) {
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.HTML,
                root,
                root,
                true,
                root,
                null,
                Set.of(),
                Set.of("html")
        );
    }

    private App app() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.HTML.getValue());
        return app;
    }

    private GenerationTaskRequest request() {
        User user = new User();
        user.setId(2L);
        return new GenerationTaskRequest(app(), "创建静态活动页", user);
    }

    private CreateGenerationPlan plan() {
        return new CreateGenerationPlan(
                CodeGenTypeEnum.HTML,
                new CreateTemplateManifest(
                        "html-landing", CodeGenTypeEnum.HTML, "HTML landing"),
                List.of(),
                List.of(new SlotGroup(
                        "html-slots", "html-landing", "base", List.of("content"), 0)),
                0.9,
                "test",
                "test",
                ""
        );
    }
}
