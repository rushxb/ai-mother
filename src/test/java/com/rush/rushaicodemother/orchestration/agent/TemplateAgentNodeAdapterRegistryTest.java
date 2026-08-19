package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.artifact.TemplateBootstrapArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TemplateAgentNodeAdapterRegistryTest {

    @Test
    void registeredAdapterMustExtendTemplateBootstrapWithoutChangingDagNode() {
        GenerationTemplateBootstrapAdapter htmlAdapter = adapter(
                CodeGenTypeEnum.HTML,
                stablePayload(CodeGenTypeEnum.HTML, "html-test")
        );
        TemplateAgentNode node = new TemplateAgentNode(registry(htmlAdapter));

        AgentNodeResult result = node.execute(context(CodeGenTypeEnum.HTML));

        assertEquals("完成", result.summary());
        assertEquals(
                "html-test",
                TemplateBootstrapArtifact.fromArtifact(result.artifacts().getFirst()).templateId()
        );
    }

    @Test
    void malformedStableTemplateFactMustFailBeforePublishingToTheDag() {
        Map<String, Object> malformed = new java.util.LinkedHashMap<>(
                stablePayload(CodeGenTypeEnum.HTML, "html-test"));
        malformed.put("fileCount", "many");
        TemplateAgentNode node = new TemplateAgentNode(registry(
                adapter(CodeGenTypeEnum.HTML, malformed)));

        assertThrows(
                IllegalArgumentException.class,
                () -> node.execute(context(CodeGenTypeEnum.HTML))
        );
    }

    @Test
    void adapterWithoutBootstrapFactMustFailClosed() {
        TemplateAgentNode node = new TemplateAgentNode(registry(
                adapter(CodeGenTypeEnum.HTML, Map.of("templateId", "invalid"))));

        assertThrows(
                IllegalStateException.class,
                () -> node.execute(context(CodeGenTypeEnum.HTML))
        );
    }

    @Test
    void duplicateProjectTypeAdaptersMustFailDuringRegistryConstruction() {
        GenerationTemplateBootstrapAdapter first = adapter(
                CodeGenTypeEnum.HTML, Map.of("bootstrapped", true));
        GenerationTemplateBootstrapAdapter duplicate = adapter(
                CodeGenTypeEnum.HTML, Map.of("bootstrapped", true));

        assertThrows(
                IllegalStateException.class,
                () -> new GenerationTemplateBootstrapRegistry(
                        mock(GenerationWorkspaceService.class),
                        List.of(first, duplicate)
                )
        );
    }

    @Test
    void missingTargetTypeMustProduceDiagnosticSkipInsteadOfRegistryFailure() {
        TemplateAgentNode node = new TemplateAgentNode(registry(
                adapter(CodeGenTypeEnum.HTML, Map.of("bootstrapped", true))));
        GenerationAgentContext context = context(CodeGenTypeEnum.HTML);
        context.setTargetType(null);

        AgentNodeResult result = node.execute(context);

        assertEquals(false, result.data().get("bootstrapped"));
        assertEquals("unsupported_template_type", result.data().get("reason"));
    }

    private GenerationTemplateBootstrapAdapter adapter(
            CodeGenTypeEnum codeGenType,
            Map<String, Object> payload
    ) {
        return new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return codeGenType;
            }

            @Override
            public GenerationTemplateBootstrapOutput bootstrap(
                    GenerationTemplateBootstrapRequest request
            ) {
                return new GenerationTemplateBootstrapOutput(
                        true,
                        "测试模板",
                        "完成",
                        payload,
                        Map.of("frontend", payload),
                        Map.of()
                );
            }
        };
    }

    private Map<String, Object> stablePayload(CodeGenTypeEnum codeGenType, String templateId) {
        return Map.of(
                "bootstrapped", true,
                "templateId", templateId,
                "projectPath", "target/test-workspaces/template-agent/" + codeGenType.getValue(),
                "fileCount", 1,
                "targetType", codeGenType.getValue(),
                "reason", ""
        );
    }

    private GenerationTemplateBootstrapRegistry registry(
            GenerationTemplateBootstrapAdapter adapter
    ) {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        when(workspaceService.resolve(1L, adapter.codeGenType()))
                .thenReturn(workspace(adapter.codeGenType()));
        return new GenerationTemplateBootstrapRegistry(workspaceService, List.of(adapter));
    }

    private GenerationWorkspace workspace(CodeGenTypeEnum codeGenType) {
        Path root = Path.of("target/test-workspaces/template-agent", codeGenType.getValue())
                .toAbsolutePath()
                .normalize();
        return new GenerationWorkspace(
                1L,
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

    private GenerationAgentContext context(CodeGenTypeEnum targetType) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(targetType.getValue());
        GenerationOrchestrationRequest request = new GenerationOrchestrationRequest(
                app,
                "创建测试项目",
                targetType,
                "create",
                false,
                prompt -> targetType,
                null
        );
        GenerationAgentContext context = new GenerationAgentContext(
                request,
                new GenerationOrchestrationTask(),
                true
        );
        context.setTargetType(targetType);
        return context;
    }
}
