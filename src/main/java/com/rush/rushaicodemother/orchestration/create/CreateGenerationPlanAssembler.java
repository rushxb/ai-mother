package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 将 adapter 选择出的模板模块归一化为有序、无重复 slot 的 CREATE 计划。 */
@Component
public class CreateGenerationPlanAssembler {

    private static final double LOCAL_RULE_CONFIDENCE = 0.82;

    /**
     * 组装最终计划，同一模板中的重复 slot 只保留首次出现位置。
     * 模块顺序即生成顺序，因此使用稳定的插入序集合而非无序去重。
     */
    CreateGenerationPlan assemble(
            CodeGenTypeEnum codeGenType,
            String baseTemplateId,
            String reason,
            List<FeatureModuleManifest> modules
    ) {
        Objects.requireNonNull(codeGenType, "CREATE 计划工程类型不能为空");
        if (baseTemplateId == null || baseTemplateId.isBlank()) {
            throw new IllegalArgumentException("CREATE 计划基础模板不能为空");
        }
        String resolvedReason = reason == null ? "" : reason;
        List<FeatureModuleManifest> resolvedModules = modules == null ? List.of() : List.copyOf(modules);
        List<SlotGroup> groups = new ArrayList<>();
        Set<String> seenTemplateSlots = new LinkedHashSet<>();
        int order = 0;
        for (FeatureModuleManifest module : resolvedModules) {
            if (module == null) {
                throw new IllegalArgumentException("CREATE 计划功能模块不能为 null");
            }
            List<String> slotIds = module.slotIds().stream()
                    .filter(slotId -> seenTemplateSlots.add(module.templateId() + ":" + slotId))
                    .toList();
            if (slotIds.isEmpty()) {
                continue;
            }
            groups.add(new SlotGroup(
                    module.moduleId() + "-slots",
                    module.templateId(),
                    module.moduleId(),
                    slotIds,
                    order++
            ));
        }
        return new CreateGenerationPlan(
                codeGenType,
                new CreateTemplateManifest(baseTemplateId, codeGenType, resolvedReason),
                resolvedModules,
                groups,
                LOCAL_RULE_CONFIDENCE,
                resolvedReason,
                "local_rules",
                "routing_model_not_configured_local_rules_used"
        );
    }
}
