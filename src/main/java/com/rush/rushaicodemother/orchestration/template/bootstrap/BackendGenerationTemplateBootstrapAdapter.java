package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.Map;
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
    public GenerationTemplateBootstrapOutput bootstrap(
            GenerationTemplateBootstrapRequest request
    ) {
        BackendProjectTemplateBootstrapService.BootstrapResult result =
                bootstrapService.bootstrapIfNecessary(request.appId(), codeGenType());
        Map<String, Object> payload = result.toPayload();
        String summary = result.bootstrapped()
                ? "已复制后端项目模板：" + result.templateId()
                : "跳过后端项目模板复制：" + result.reason();
        return new GenerationTemplateBootstrapOutput(
                result.bootstrapped(),
                "后端项目模板",
                summary,
                payload,
                Map.of("backend", payload),
                Map.of()
        );
    }
}
