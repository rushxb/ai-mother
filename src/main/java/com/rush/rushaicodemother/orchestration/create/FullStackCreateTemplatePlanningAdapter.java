package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Vue + Go SQLite 全栈项目的 CREATE 模板规划 adapter。 */
@Component
public class FullStackCreateTemplatePlanningAdapter implements CreateTemplatePlanningAdapter {

    private static final String PLAN_REASON = "Vue + Go SQLite 全栈 CRUD 模板计划";

    private final VueTemplateFeaturePlanner frontendFeaturePlanner;
    private final BackendTemplateFeaturePlanner backendFeaturePlanner;
    private final CreateGenerationPlanAssembler planAssembler;

    public FullStackCreateTemplatePlanningAdapter(
            VueTemplateFeaturePlanner frontendFeaturePlanner,
            BackendTemplateFeaturePlanner backendFeaturePlanner,
            CreateGenerationPlanAssembler planAssembler
    ) {
        this.frontendFeaturePlanner = frontendFeaturePlanner;
        this.backendFeaturePlanner = backendFeaturePlanner;
        this.planAssembler = planAssembler;
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    @Override
    public CreateGenerationPlan plan(String userMessage) {
        VueTemplateFeaturePlanner.VueTemplateFeaturePlan frontend =
                frontendFeaturePlanner.planFullStackFrontend(userMessage);
        BackendTemplateFeaturePlanner.BackendTemplateFeaturePlan backend =
                backendFeaturePlanner.plan(userMessage);
        List<FeatureModuleManifest> modules = new ArrayList<>(frontend.modules());
        modules.addAll(backend.modules());
        return planAssembler.assemble(
                codeGenType(),
                frontend.baseTemplateId() + "+" + backend.baseTemplateId(),
                PLAN_REASON,
                modules
        );
    }
}
