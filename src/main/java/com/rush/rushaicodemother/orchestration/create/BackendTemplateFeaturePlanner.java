package com.rush.rushaicodemother.orchestration.create;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Go SQLite 后端模板与功能模块规则的 deep module。
 *
 * <p>独立后端与全栈 CREATE 共享同一份基础 CRUD、搜索、分页和导入导出事实，
 * 避免两个工程类型 adapter 分别解释同一条后端需求。</p>
 */
@Component
public class BackendTemplateFeaturePlanner {

    private static final String BACKEND_TEMPLATE = "go-sqlite-backend-basic";

    /** 根据用户需求规划完整的后端模板能力。 */
    BackendTemplateFeaturePlan plan(String userMessage) {
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
        for (BackendRecipeCapability capability : BackendRecipeCapability.values()) {
            if (capability.matches(userMessage)) {
                modules.add(capability.plannedModule(BACKEND_TEMPLATE));
            }
        }
        return new BackendTemplateFeaturePlan(BACKEND_TEMPLATE, modules);
    }

    private FeatureModuleManifest module(
            String moduleId,
            String name,
            List<String> slotIds,
            String reason
    ) {
        return new FeatureModuleManifest(moduleId, name, BACKEND_TEMPLATE, slotIds, reason);
    }

    /** 后端模板规划的内部不可变结果。 */
    record BackendTemplateFeaturePlan(
            String baseTemplateId,
            List<FeatureModuleManifest> modules
    ) {
        BackendTemplateFeaturePlan {
            if (baseTemplateId == null || baseTemplateId.isBlank()) {
                throw new IllegalArgumentException("后端基础模板不能为空");
            }
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }
}
