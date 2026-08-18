package com.rush.rushaicodemother.orchestration.agent.template;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** Go 后端新项目模板初始化 adapter。 */
@Component
public final class BackendGenerationTemplateBootstrapAdapter
        implements GenerationTemplateBootstrapAdapter {

    private final BackendProjectTemplateBootstrapService bootstrapService;

    public BackendGenerationTemplateBootstrapAdapter(
            BackendProjectTemplateBootstrapService bootstrapService
    ) {
        this.bootstrapService = Objects.requireNonNull(
                bootstrapService, "后端模板初始化服务不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.BACKEND_PROJECT;
    }

    @Override
    public AgentNodeResult bootstrap(GenerationAgentContext context) {
        App app = requireApp(context);
        BackendProjectTemplateBootstrapService.BootstrapResult result =
                bootstrapService.bootstrapIfNecessary(app.getId(), codeGenType());
        GenerationArtifact artifact = GenerationArtifact.of(
                "template_bootstrap",
                "Template",
                "后端项目模板",
                result.toPayload()
        );
        String summary = result.bootstrapped()
                ? "已复制后端项目模板：" + result.templateId()
                : "跳过后端项目模板复制：" + result.reason();
        return AgentNodeResult.of(summary, List.of(artifact), artifact.payload());
    }

    private App requireApp(GenerationAgentContext context) {
        if (context == null || context.getRequest() == null
                || context.getRequest().app() == null
                || context.getRequest().app().getId() == null) {
            throw new IllegalArgumentException("后端模板初始化缺少应用身份");
        }
        return context.getRequest().app();
    }
}
