package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Vue + Go SQLite 全栈项目的 CREATE 模板规划 adapter。 */
@Component
public class FullStackCreateTemplatePlanningAdapter implements CreateTemplatePlanningAdapter {

    private static final String PLAN_REASON = "Vue + Go SQLite 全栈 CRUD 模板计划";

    private final VueTemplateFeaturePlanner featurePlanner;
    private final CreateGenerationPlanAssembler planAssembler;

    public FullStackCreateTemplatePlanningAdapter(
            VueTemplateFeaturePlanner featurePlanner,
            CreateGenerationPlanAssembler planAssembler
    ) {
        this.featurePlanner = featurePlanner;
        this.planAssembler = planAssembler;
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    @Override
    public CreateGenerationPlan plan(String userMessage) {
        VueTemplateFeaturePlanner.VueTemplateFeaturePlan frontend =
                featurePlanner.planFullStackFrontend(userMessage);
        List<FeatureModuleManifest> modules = new ArrayList<>(frontend.modules());
        modules.add(new FeatureModuleManifest(
                "backend-crud-api",
                "Go SQLite CRUD API",
                BackendCreateTemplatePlanningAdapter.BACKEND_TEMPLATE,
                List.of(
                        "domain_contract",
                        "module_model",
                        "module_repository",
                        "module_service",
                        "module_handler",
                        "database_schema",
                        "module_import",
                        "server_wiring"
                ),
                "全栈 CRUD 需要后端 API、schema 和启动装配"
        ));
        return planAssembler.assemble(
                codeGenType(),
                frontend.baseTemplateId() + "+" + BackendCreateTemplatePlanningAdapter.BACKEND_TEMPLATE,
                PLAN_REASON,
                modules
        );
    }
}
