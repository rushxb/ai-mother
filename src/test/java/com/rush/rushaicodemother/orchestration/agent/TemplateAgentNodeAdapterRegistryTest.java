package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.agent.template.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TemplateAgentNodeAdapterRegistryTest {

    @Test
    void registeredAdapterMustExtendTemplateBootstrapWithoutChangingDagNode() {
        GenerationTemplateBootstrapAdapter htmlAdapter = new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public AgentNodeResult bootstrap(GenerationAgentContext context) {
                GenerationArtifact artifact = GenerationArtifact.of(
                        "template_bootstrap",
                        "Template",
                        "HTML 测试模板",
                        Map.of("bootstrapped", true, "templateId", "html-test")
                );
                return AgentNodeResult.of(
                        "HTML 模板已初始化",
                        List.of(artifact),
                        artifact.payload()
                );
            }
        };
        TemplateAgentNode node = new TemplateAgentNode(List.of(htmlAdapter));

        AgentNodeResult result = node.execute(context(CodeGenTypeEnum.HTML));

        assertEquals("HTML 模板已初始化", result.summary());
        assertEquals("html-test", result.artifacts().getFirst().payload().get("templateId"));
    }

    @Test
    void adapterWithoutTemplateBootstrapArtifactMustFailClosed() {
        GenerationTemplateBootstrapAdapter invalidAdapter = new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return CodeGenTypeEnum.HTML;
            }

            @Override
            public AgentNodeResult bootstrap(GenerationAgentContext context) {
                return AgentNodeResult.of(
                        "错误结果",
                        List.of(GenerationArtifact.of(
                                "unrelated_artifact", "Template", "错误产物", Map.of())),
                        Map.of()
                );
            }
        };
        TemplateAgentNode node = new TemplateAgentNode(List.of(invalidAdapter));

        assertThrows(
                IllegalStateException.class,
                () -> node.execute(context(CodeGenTypeEnum.HTML))
        );
    }

    @Test
    void duplicateProjectTypeAdaptersMustFailDuringNodeConstruction() {
        GenerationTemplateBootstrapAdapter first = adapter(CodeGenTypeEnum.HTML);
        GenerationTemplateBootstrapAdapter duplicate = adapter(CodeGenTypeEnum.HTML);

        assertThrows(
                IllegalStateException.class,
                () -> new TemplateAgentNode(List.of(first, duplicate))
        );
    }

    @Test
    void missingTargetTypeMustProduceDiagnosticSkipInsteadOfRegistryFailure() {
        TemplateAgentNode node = new TemplateAgentNode(
                List.of(adapter(CodeGenTypeEnum.HTML)));
        GenerationAgentContext context = context(CodeGenTypeEnum.HTML);
        context.setTargetType(null);

        AgentNodeResult result = node.execute(context);

        assertEquals(false, result.data().get("bootstrapped"));
        assertEquals("unsupported_template_type", result.data().get("reason"));
    }

    private GenerationTemplateBootstrapAdapter adapter(CodeGenTypeEnum codeGenType) {
        return new GenerationTemplateBootstrapAdapter() {
            @Override
            public CodeGenTypeEnum codeGenType() {
                return codeGenType;
            }

            @Override
            public AgentNodeResult bootstrap(GenerationAgentContext context) {
                GenerationArtifact artifact = GenerationArtifact.of(
                        "template_bootstrap", "Template", "测试模板", Map.of());
                return AgentNodeResult.of("完成", List.of(artifact), artifact.payload());
            }
        };
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
