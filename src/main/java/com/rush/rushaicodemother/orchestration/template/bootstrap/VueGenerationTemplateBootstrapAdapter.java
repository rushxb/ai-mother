package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Objects;

/** Vue 新项目模板初始化 adapter。 */
@Component
public final class VueGenerationTemplateBootstrapAdapter
        implements GenerationTemplateBootstrapAdapter {

    private final VueProjectTemplateBootstrapService bootstrapService;

    public VueGenerationTemplateBootstrapAdapter(
            VueProjectTemplateBootstrapService bootstrapService
    ) {
        this.bootstrapService = Objects.requireNonNull(
                bootstrapService, "Vue 模板初始化服务不能为空");
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public GenerationTemplateBootstrapOutput bootstrap(
            GenerationTemplateBootstrapRequest request
    ) {
        VueProjectTemplateBootstrapService.BootstrapResult result =
                bootstrapService.bootstrapIfNecessary(
                        request.appId(), codeGenType(), request.userMessage());
        Map<String, Object> payload = result.toPayload();
        String summary = result.bootstrapped()
                ? "已复制 Vue 项目模板：" + result.templateId()
                : "跳过 Vue 项目模板复制：" + result.reason();
        return new GenerationTemplateBootstrapOutput(
                result.bootstrapped(),
                "Vue 项目模板",
                summary,
                payload,
                Map.of("frontend", payload),
                Map.of()
        );
    }
}
