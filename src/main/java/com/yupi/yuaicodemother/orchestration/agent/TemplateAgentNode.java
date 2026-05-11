package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Template：为首次生成的 Vue 工程复制项目模板。
 */
@Component
public class TemplateAgentNode extends BaseGenerationAgentNode {

    private final VueProjectTemplateBootstrapService templateBootstrapService;

    public TemplateAgentNode(VueProjectTemplateBootstrapService templateBootstrapService) {
        super("template", "Template", "template", List.of("planner"));
        this.templateBootstrapService = templateBootstrapService;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        if (context.getTargetType() != CodeGenTypeEnum.VUE_PROJECT
                || context.getRequest().hasGeneratedCode()
                || app == null
                || app.getId() == null) {
            GenerationArtifact skipped = GenerationArtifact.of(
                    "template_bootstrap",
                    "Template",
                    "Vue 项目模板",
                    Map.of(
                            "bootstrapped", false,
                            "templateId", "",
                            "projectPath", "",
                            "fileCount", 0,
                            "reason", "not_new_vue_project"
                    )
            );
            return AgentNodeResult.of("无需复制 Vue 项目模板", List.of(skipped), skipped.payload());
        }
        VueProjectTemplateBootstrapService.BootstrapResult result =
                templateBootstrapService.bootstrapIfNecessary(app.getId(), context.getRequest().userMessage());
        GenerationArtifact artifact = GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                "Vue 项目模板",
                result.toPayload()
        );
        String summary = result.bootstrapped()
                ? "已复制 Vue 项目模板：" + result.templateId()
                : "跳过 Vue 项目模板复制：" + result.reason();
        return AgentNodeResult.of(summary, List.of(artifact), artifact.payload());
    }
}
