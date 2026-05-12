package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.yupi.yuaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class TemplateAgentNode extends BaseGenerationAgentNode {

    private final VueProjectTemplateBootstrapService vueTemplateBootstrapService;
    private final BackendProjectTemplateBootstrapService backendTemplateBootstrapService;

    public TemplateAgentNode(VueProjectTemplateBootstrapService vueTemplateBootstrapService,
                             BackendProjectTemplateBootstrapService backendTemplateBootstrapService) {
        super("template", "Template", "template", List.of("planner"));
        this.vueTemplateBootstrapService = vueTemplateBootstrapService;
        this.backendTemplateBootstrapService = backendTemplateBootstrapService;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        CodeGenTypeEnum targetType = context.getTargetType();
        if (context.getRequest().hasGeneratedCode() || app == null || app.getId() == null) {
            return skipped("无需复制项目模板", targetType, "not_new_project");
        }
        if (targetType == CodeGenTypeEnum.VUE_PROJECT) {
            VueProjectTemplateBootstrapService.BootstrapResult result =
                    vueTemplateBootstrapService.bootstrapIfNecessary(app.getId(), context.getRequest().userMessage());
            return result("Vue 项目模板", result.toPayload(), result.bootstrapped()
                    ? "已复制 Vue 项目模板：" + result.templateId()
                    : "跳过 Vue 项目模板复制：" + result.reason());
        }
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            BackendProjectTemplateBootstrapService.BootstrapResult result =
                    backendTemplateBootstrapService.bootstrapIfNecessary(app.getId());
            return result("后端项目模板", result.toPayload(), result.bootstrapped()
                    ? "已复制后端项目模板：" + result.templateId()
                    : "跳过后端项目模板复制：" + result.reason());
        }
        return skipped("无需复制项目模板", targetType, "unsupported_template_type");
    }

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

    private AgentNodeResult result(String name, Map<String, Object> payload, String summary) {
        GenerationArtifact artifact = GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                name,
                payload
        );
        return AgentNodeResult.of(summary, List.of(artifact), artifact.payload());
    }
}
