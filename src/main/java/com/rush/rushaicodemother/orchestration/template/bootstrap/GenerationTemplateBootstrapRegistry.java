package com.rush.rushaicodemother.orchestration.template.bootstrap;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 模板初始化的唯一事实所有者。
 *
 * <p>调用方只提交应用、工程类型和消息；工作区解析、adapter 选择与结果契约均在此 fail-closed。</p>
 */
@Component
public final class GenerationTemplateBootstrapRegistry {

    private final GenerationWorkspaceService workspaceService;
    private final Map<CodeGenTypeEnum, GenerationTemplateBootstrapAdapter> adaptersByType;

    public GenerationTemplateBootstrapRegistry(
            GenerationWorkspaceService workspaceService,
            List<GenerationTemplateBootstrapAdapter> adapters
    ) {
        this.workspaceService = Objects.requireNonNull(
                workspaceService, "模板初始化工作区服务不能为空");
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个模板初始化 adapter");
        }
        EnumMap<CodeGenTypeEnum, GenerationTemplateBootstrapAdapter> registered =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationTemplateBootstrapAdapter adapter : adapters) {
            if (adapter == null || adapter.codeGenType() == null) {
                throw new IllegalStateException("模板初始化 adapter 必须声明工程类型");
            }
            if (registered.putIfAbsent(adapter.codeGenType(), adapter) != null) {
                throw new IllegalStateException(
                        "工程类型存在重复模板初始化 adapter: "
                                + adapter.codeGenType().getValue());
            }
        }
        this.adaptersByType = Map.copyOf(registered);
    }

    /** 初始化指定工程类型；未注册类型返回稳定的 unsupported 结果。 */
    public GenerationTemplateBootstrapResult bootstrap(
            Long appId,
            CodeGenTypeEnum codeGenType,
            String userMessage
    ) {
        if (codeGenType == null) {
            return GenerationTemplateBootstrapResult.unsupported(
                    null, "unsupported_template_type");
        }
        GenerationTemplateBootstrapAdapter adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            return GenerationTemplateBootstrapResult.unsupported(
                    codeGenType, "unsupported_template_type");
        }
        if (appId == null) {
            throw new IllegalArgumentException("模板初始化缺少应用身份");
        }
        GenerationWorkspace workspace = Objects.requireNonNull(
                workspaceService.resolve(appId, codeGenType),
                "模板初始化工作区解析结果不能为空");
        if (workspace.codeGenType() != codeGenType) {
            throw new IllegalStateException(
                    "模板初始化工作区类型不匹配: expected=" + codeGenType.getValue()
                            + ", actual="
                            + (workspace.codeGenType() == null
                            ? "null" : workspace.codeGenType().getValue()));
        }
        GenerationTemplateBootstrapRequest request =
                new GenerationTemplateBootstrapRequest(appId, userMessage, workspace);
        GenerationTemplateBootstrapOutput output = Objects.requireNonNull(
                adapter.bootstrap(request),
                "模板初始化 adapter 返回结果不能为空: " + codeGenType.getValue());
        if (!output.templatePayload().containsKey("bootstrapped")) {
            throw new IllegalStateException(
                    "模板初始化 adapter 未返回 bootstrapped 事实: " + codeGenType.getValue());
        }
        return GenerationTemplateBootstrapResult.completed(
                codeGenType, workspace, output);
    }
}
