package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapRegistry;
import com.rush.rushaicodemother.orchestration.template.bootstrap.GenerationTemplateBootstrapResult;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板智能体节点。
 */
@Component
public class TemplateAgentNode extends BaseGenerationAgentNode {

    private final GenerationTemplateBootstrapRegistry templateBootstrapRegistry;

    public TemplateAgentNode(GenerationTemplateBootstrapRegistry templateBootstrapRegistry) {
        super("template", "Template", "template", List.of("planner"));
        this.templateBootstrapRegistry = java.util.Objects.requireNonNull(
                templateBootstrapRegistry, "模板初始化 registry 不能为空");
    }

    /**
 * 执行模板智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return 模板智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        CodeGenTypeEnum targetType = context.getTargetType();
        if (context.getRequest().hasGeneratedCode() || app == null || app.getId() == null) {
            return skipped("无需复制项目模板", targetType, "not_new_project");
        }
        GenerationTemplateBootstrapResult result = templateBootstrapRegistry.bootstrap(
                app.getId(), targetType, context.getRequest().userMessage());
        if (!result.supported()) {
            return skipped("无需复制项目模板", targetType, "unsupported_template_type");
        }
        List<GenerationArtifact> artifacts = new ArrayList<>();
        artifacts.add(GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                result.artifactName(),
                result.templatePayload()
        ));
        if (!result.contextPayload().isEmpty()) {
            artifacts.add(GenerationArtifact.of(
                    "full_stack_context",
                    "Template",
                    "全栈上下文",
                    result.contextPayload()
            ));
        }
        return AgentNodeResult.of(result.summary(), artifacts, result.templatePayload());
    }

    /** 返回{@code skipped}。 */
    private AgentNodeResult skipped(String summary, CodeGenTypeEnum targetType, String reason) {
        GenerationArtifact skipped = GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                "项目模板",
                Map.of(
                        "bootstrapped", false,
                        "templateId", "",
                        "projectPath", "",
                        "fileCount", 0,
                        "targetType", targetType == null ? "" : targetType.getValue(),
                        "reason", reason
                )
        );
        return AgentNodeResult.of(summary, List.of(skipped), skipped.payload());
    }

}
