package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.agent.template.GenerationTemplateBootstrapAdapter;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 模板智能体节点。
 */
@Component
public class TemplateAgentNode extends BaseGenerationAgentNode {

    private final Map<CodeGenTypeEnum, GenerationTemplateBootstrapAdapter> adaptersByType;

    /** 构建不可变的模板初始化 adapter 注册表。 */
    public TemplateAgentNode(List<GenerationTemplateBootstrapAdapter> adapters) {
        super("template", "Template", "template", List.of("planner"));
        EnumMap<CodeGenTypeEnum, GenerationTemplateBootstrapAdapter> registered =
                new EnumMap<>(CodeGenTypeEnum.class);
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("至少需要注册一个模板初始化 adapter");
        }
        for (GenerationTemplateBootstrapAdapter adapter : adapters) {
            if (adapter == null || adapter.codeGenType() == null) {
                throw new IllegalStateException("模板初始化 adapter 必须声明工程类型");
            }
            if (registered.putIfAbsent(adapter.codeGenType(), adapter) != null) {
                throw new IllegalStateException(
                        "工程类型存在重复模板初始化 adapter: " + adapter.codeGenType().getValue());
            }
        }
        this.adaptersByType = Map.copyOf(registered);
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
        if (targetType == null) {
            return skipped("无需复制项目模板", null, "unsupported_template_type");
        }
        GenerationTemplateBootstrapAdapter adapter = adaptersByType.get(targetType);
        if (adapter != null) {
            return requireValidBootstrapResult(adapter, adapter.bootstrap(context));
        }
        return skipped("无需复制项目模板", targetType, "unsupported_template_type");
    }

    /** 拒绝无法被后续节点和检查点识别的 adapter 结果。 */
    private AgentNodeResult requireValidBootstrapResult(
            GenerationTemplateBootstrapAdapter adapter,
            AgentNodeResult result
    ) {
        if (result == null || result.artifacts() == null
                || result.artifacts().stream().noneMatch(artifact ->
                artifact != null && "template_bootstrap".equals(artifact.key()))) {
            throw new IllegalStateException(
                    "模板初始化 adapter 未返回 template_bootstrap 制品: "
                            + adapter.codeGenType().getValue());
        }
        return result;
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
