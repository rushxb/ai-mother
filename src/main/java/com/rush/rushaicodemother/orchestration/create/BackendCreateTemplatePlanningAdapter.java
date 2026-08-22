package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

/** Go SQLite 后端项目的 CREATE 模板规划 adapter。 */
@Component
public class BackendCreateTemplatePlanningAdapter implements CreateTemplatePlanningAdapter {

    private static final String PLAN_REASON = "Go SQLite 后端 CRUD 模板计划";

    private final BackendTemplateFeaturePlanner featurePlanner;
    private final CreateGenerationPlanAssembler planAssembler;

    public BackendCreateTemplatePlanningAdapter(
            BackendTemplateFeaturePlanner featurePlanner,
            CreateGenerationPlanAssembler planAssembler
    ) {
        this.featurePlanner = featurePlanner;
        this.planAssembler = planAssembler;
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.BACKEND_PROJECT;
    }

    @Override
    public CreateGenerationPlan plan(String userMessage) {
        BackendTemplateFeaturePlanner.BackendTemplateFeaturePlan backend =
                featurePlanner.plan(userMessage);
        return planAssembler.assemble(
                codeGenType(),
                backend.baseTemplateId(),
                PLAN_REASON,
                backend.modules()
        );
    }
}
