package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

/** Vue 项目的 CREATE 模板规划 adapter。 */
@Component
public class VueCreateTemplatePlanningAdapter implements CreateTemplatePlanningAdapter {

    private static final String DEFAULT_REASON = "Vue 首次生成模板计划";
    private static final String LANDING_REASON =
            "Vue landing 首次生成模板计划：首轮只填充页面实际消费的核心数据，复杂扩展交给 EDIT 精准增强";

    private final VueTemplateFeaturePlanner featurePlanner;
    private final CreateGenerationPlanAssembler planAssembler;

    public VueCreateTemplatePlanningAdapter(
            VueTemplateFeaturePlanner featurePlanner,
            CreateGenerationPlanAssembler planAssembler
    ) {
        this.featurePlanner = featurePlanner;
        this.planAssembler = planAssembler;
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.VUE_PROJECT;
    }

    @Override
    public CreateGenerationPlan plan(String userMessage) {
        VueTemplateFeaturePlanner.VueTemplateFeaturePlan frontend =
                featurePlanner.planStandalone(userMessage);
        String reason = "vue-web-landing".equals(frontend.baseTemplateId())
                ? LANDING_REASON
                : DEFAULT_REASON;
        return planAssembler.assemble(
                codeGenType(),
                frontend.baseTemplateId(),
                reason,
                frontend.modules()
        );
    }
}
