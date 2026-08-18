package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.rush.rushaicodemother.orchestration.create.CreatePromptKeywordMatcher.containsAny;

/** Go SQLite 后端项目的 CREATE 模板规划 adapter。 */
@Component
public class BackendCreateTemplatePlanningAdapter implements CreateTemplatePlanningAdapter {

    static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";
    private static final String PLAN_REASON = "Go SQLite 后端 CRUD 模板计划";

    private final CreateGenerationPlanAssembler planAssembler;

    public BackendCreateTemplatePlanningAdapter(CreateGenerationPlanAssembler planAssembler) {
        this.planAssembler = planAssembler;
    }

    @Override
    public CodeGenTypeEnum codeGenType() {
        return CodeGenTypeEnum.BACKEND_PROJECT;
    }

    @Override
    public CreateGenerationPlan plan(String userMessage) {
        List<FeatureModuleManifest> modules = new ArrayList<>();
        modules.add(module(
                "backend-crud-api",
                "Go SQLite CRUD API",
                List.of("domain_contract", "module_model", "module_repository", "module_service", "module_handler"),
                "后端 CREATE 默认生成 CRUD 分层模块"
        ));
        modules.add(module(
                "sqlite-schema",
                "SQLite Schema",
                List.of("database_schema"),
                "CRUD 后端需要 schema 与 repository 保持一致"
        ));
        modules.add(module(
                "server-wiring",
                "服务装配",
                List.of("module_import", "server_wiring"),
                "新增模块需要注册到服务启动入口"
        ));
        if (containsAny(userMessage, "搜索", "筛选", "filter", "search", "查询")) {
            modules.add(module(
                    "backend-search",
                    "后端搜索扩展",
                    List.of("module_search"),
                    "后端搜索需要模糊查询和分页能力"
            ));
        }
        if (containsAny(userMessage, "导入", "导出", "批量", "csv", "excel", "import", "export")) {
            modules.add(module(
                    "backend-export",
                    "后端导入导出",
                    List.of("module_import_export"),
                    "后端导入导出需要 CSV/JSON 批量操作"
            ));
        }
        if (containsAny(userMessage, "分页", "pagination", "列表", "page")) {
            modules.add(module(
                    "backend-pagination",
                    "后端分页扩展",
                    List.of("module_pagination"),
                    "后端分页需要统一分页查询辅助"
            ));
        }
        return planAssembler.assemble(
                codeGenType(),
                BACKEND_TEMPLATE,
                PLAN_REASON,
                modules
        );
    }

    private FeatureModuleManifest module(
            String moduleId,
            String name,
            List<String> slotIds,
            String reason
    ) {
        return new FeatureModuleManifest(moduleId, name, BACKEND_TEMPLATE, slotIds, reason);
    }
}
