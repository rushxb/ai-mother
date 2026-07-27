package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板智能体节点。
 */
@Component
public class TemplateAgentNode extends BaseGenerationAgentNode {

    private final VueProjectTemplateBootstrapService vueTemplateBootstrapService;
    private final BackendProjectTemplateBootstrapService backendTemplateBootstrapService;
    private final FullStackPortAllocator fullStackPortAllocator;

    public TemplateAgentNode(VueProjectTemplateBootstrapService vueTemplateBootstrapService,
                             BackendProjectTemplateBootstrapService backendTemplateBootstrapService,
                             FullStackPortAllocator fullStackPortAllocator) {
        super("template", "Template", "template", List.of("planner"));
        this.vueTemplateBootstrapService = vueTemplateBootstrapService;
        this.backendTemplateBootstrapService = backendTemplateBootstrapService;
        this.fullStackPortAllocator = fullStackPortAllocator;
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
                    vueTemplateBootstrapService.bootstrapIfNecessary(app.getId(), targetType, context.getRequest().userMessage());
            return result("Vue 项目模板", result.toPayload(), result.bootstrapped()
                    ? "已复制 Vue 项目模板：" + result.templateId()
                    : "跳过 Vue 项目模板复制：" + result.reason());
        }
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            BackendProjectTemplateBootstrapService.BootstrapResult result =
                    backendTemplateBootstrapService.bootstrapIfNecessary(app.getId(), targetType);
            return result("后端项目模板", result.toPayload(), result.bootstrapped()
                    ? "已复制后端项目模板：" + result.templateId()
                    : "跳过后端项目模板复制：" + result.reason());
        }
        if (targetType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return bootstrapFullStackProject(context, app);
        }
        return skipped("无需复制项目模板", targetType, "unsupported_template_type");
    }

    private AgentNodeResult bootstrapFullStackProject(GenerationAgentContext context, App app) {
        FullStackGenerationContext fullStackContext = fullStackPortAllocator.allocate(app.getId());
        VueProjectTemplateBootstrapService.BootstrapResult frontendResult =
                vueTemplateBootstrapService.bootstrapIfNecessary(
                        app.getId(),
                        CodeGenTypeEnum.FULL_STACK_PROJECT,
                        context.getRequest().userMessage()
                );
        BackendProjectTemplateBootstrapService.BootstrapResult backendResult =
                backendTemplateBootstrapService.bootstrapIfNecessary(app.getId(), CodeGenTypeEnum.FULL_STACK_PROJECT);
        Map<String, Object> payload = new LinkedHashMap<>(fullStackContext.toPayload());
        payload.put("bootstrapped", frontendResult.bootstrapped() || backendResult.bootstrapped());
        payload.put("templateId", frontendResult.templateId() + "+" + backendResult.templateId());
        payload.put("frontendTemplateId", frontendResult.templateId());
        payload.put("backendTemplateId", backendResult.templateId());
        payload.put("projectPath", fullStackContext.workspaceRoot());
        payload.put("fileCount", frontendResult.fileCount() + backendResult.fileCount());
        payload.put("reason", frontendResult.reason().isBlank() ? backendResult.reason() : frontendResult.reason());
        GenerationArtifact contextArtifact = GenerationArtifact.of(
                "full_stack_context",
                "Template",
                "全栈上下文",
                fullStackContext.toPayload()
        );
        GenerationArtifact templateArtifact = GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                "全栈项目模板",
                payload
        );
        return AgentNodeResult.of(
                "已准备全栈项目模板与端口上下文",
                List.of(templateArtifact, contextArtifact),
                payload
        );
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
