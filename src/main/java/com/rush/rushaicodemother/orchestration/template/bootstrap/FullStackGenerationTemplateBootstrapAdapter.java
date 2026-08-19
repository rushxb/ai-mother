package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackGenerationContext;
import com.rush.rushaicodemother.orchestration.fullstack.FullStackPortAllocator;
import com.rush.rushaicodemother.orchestration.template.BackendProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
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
    public GenerationTemplateBootstrapOutput bootstrap(
            GenerationTemplateBootstrapRequest request
    ) {
        FullStackGenerationContext fullStackContext = portAllocator.allocate(request.workspace());
        VueProjectTemplateBootstrapService.BootstrapResult frontendResult =
                vueBootstrapService.bootstrapIfNecessary(
                        request.appId(), codeGenType(), request.userMessage());
        BackendProjectTemplateBootstrapService.BootstrapResult backendResult =
                backendBootstrapService.bootstrapIfNecessary(request.appId(), codeGenType());

        Map<String, Object> frontendPayload = frontendResult.toPayload();
        Map<String, Object> backendPayload = backendResult.toPayload();
        Map<String, Object> templatePayload = new LinkedHashMap<>(fullStackContext.toPayload());
        templatePayload.put(
                "bootstrapped",
                frontendResult.bootstrapped() || backendResult.bootstrapped());
        templatePayload.put(
                "templateId",
                frontendResult.templateId() + "+" + backendResult.templateId());
        templatePayload.put("frontendTemplateId", frontendResult.templateId());
        templatePayload.put("backendTemplateId", backendResult.templateId());
        templatePayload.put("projectPath", fullStackContext.workspaceRoot());
        templatePayload.put(
                "fileCount",
                frontendResult.fileCount() + backendResult.fileCount());
        templatePayload.put(
                "reason",
                frontendResult.reason().isBlank()
                        ? backendResult.reason()
                        : frontendResult.reason());

        return new GenerationTemplateBootstrapOutput(
                frontendResult.bootstrapped() && backendResult.bootstrapped(),
                "全栈项目模板",
                "已准备全栈项目模板与端口上下文",
                templatePayload,
                Map.of("frontend", frontendPayload, "backend", backendPayload),
                fullStackContext.toPayload()
        );
    }
}
