package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.List;
import java.util.Map;

/**
 * 创建生成计划的不可变数据载体。
 */
public record CreateGenerationPlan(
        CodeGenTypeEnum codeGenType,
        CreateTemplateManifest baseTemplate,
        List<FeatureModuleManifest> modules,
        List<SlotGroup> slotGroups,
        double confidence,
        String reason,
        String plannerSource,
        String fallbackReason
) {
    public CreateGenerationPlan {
        modules = modules == null ? List.of() : List.copyOf(modules);
        slotGroups = slotGroups == null ? List.of() : List.copyOf(slotGroups);
        reason = reason == null ? "" : reason;
        plannerSource = plannerSource == null ? "local_rules" : plannerSource;
        fallbackReason = fallbackReason == null ? "" : fallbackReason;
    }

    public String baseTemplateId() {
        return baseTemplate == null ? "" : baseTemplate.templateId();
    }

    /**
 * 返回模块{@code Ids}。
 *
 * @return 创建生成计划集合
 */
    public List<String> moduleIds() {
        return modules.stream().map(FeatureModuleManifest::moduleId).toList();
    }

    /**
 * 将当前对象转换为载荷。
 *
 * @return 载荷集合
 */
    public Map<String, Object> toPayload() {
        return Map.of(
                "codeGenType", codeGenType == null ? "" : codeGenType.getValue(),
                "baseTemplate", baseTemplateId(),
                "modules", moduleIds(),
                "slotGroups", slotGroups.size(),
                "confidence", confidence,
                "reason", reason,
                "plannerSource", plannerSource,
                "fallbackReason", fallbackReason
        );
    }
}
