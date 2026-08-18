package com.rush.rushaicodemother.orchestration.agent.template;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Vue + Go 全栈新项目模板及端口上下文初始化 adapter。 */
@Component
public final class FullStackGenerationTemplateBootstrapAdapter
        implements GenerationTemplateBootstrapAdapter {

    private final VueProjectTemplateBootstrapService vueBootstrapService;
    private final BackendProjectTemplateBootstrapService backendBootstrapService;
    private final FullStackPortAllocator portAllocator;

    public FullStackGenerationTemplateBootstrapAdapter(
            VueProjectTemplateBootstrapService vueBootstrapService,
            BackendProjectTemplateBootstrapService backendBootstrapService,
            FullStackPortAllocator portAllocator
    ) {
        this.vueBootstrapService = Objects.requireNonNull(
                vueBootstrapService, "Vue 模板初始化服务不能为空");
        this.backendBootstrapService = Objects.requireNonNull(
                backendBootstrapService, "后端模板初始化服务不能为空");
        this.portAllocator = Objects.requireNonNull(
                portAllocator, "全栈端口分配器不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    @Override
    public AgentNodeResult bootstrap(GenerationAgentContext context) {
        App app = requireApp(context);
        FullStackGenerationContext fullStackContext = portAllocator.allocate(app.getId());
        VueProjectTemplateBootstrapService.BootstrapResult frontendResult =
                vueBootstrapService.bootstrapIfNecessary(
                        app.getId(), codeGenType(), context.getRequest().userMessage());
        BackendProjectTemplateBootstrapService.BootstrapResult backendResult =
                backendBootstrapService.bootstrapIfNecessary(app.getId(), codeGenType());

        Map<String, Object> payload = new LinkedHashMap<>(fullStackContext.toPayload());
        payload.put("bootstrapped", frontendResult.bootstrapped() || backendResult.bootstrapped());
        payload.put("templateId", frontendResult.templateId() + "+" + backendResult.templateId());
        payload.put("frontendTemplateId", frontendResult.templateId());
        payload.put("backendTemplateId", backendResult.templateId());
        payload.put("projectPath", fullStackContext.workspaceRoot());
        payload.put("fileCount", frontendResult.fileCount() + backendResult.fileCount());
        payload.put("reason", frontendResult.reason().isBlank()
                ? backendResult.reason() : frontendResult.reason());

        GenerationArtifact templateArtifact = GenerationArtifact.of(
                "template_bootstrap", "Template", "全栈项目模板", payload);
        GenerationArtifact contextArtifact = GenerationArtifact.of(
                "full_stack_context",
                "Template",
                "全栈上下文",
                fullStackContext.toPayload()
        );
        return AgentNodeResult.of(
                "已准备全栈项目模板与端口上下文",
                List.of(templateArtifact, contextArtifact),
                payload
        );
    }

    private App requireApp(GenerationAgentContext context) {
        if (context == null || context.getRequest() == null
                || context.getRequest().app() == null
                || context.getRequest().app().getId() == null) {
            throw new IllegalArgumentException("全栈模板初始化缺少应用身份");
        }
        return context.getRequest().app();
    }
}
