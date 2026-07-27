package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipe;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/**
 * Planner：需求拆解与路由策略。
 */
@Component
public class PlannerAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;
    private final GenerationRoutingSupport routingSupport;

    public PlannerAgentNode(GenerationAgentSupport support, GenerationRoutingSupport routingSupport) {
        super("planner", "Planner", "planning", List.of(), GenerationNodeReplayPolicy.REPLAY_SAFE);
        this.support = support;
        this.routingSupport = routingSupport;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String userMessage = StrUtil.blankToDefault(context.getRequest().userMessage(), "");
        boolean complex = support.isComplexRequest(userMessage);
        boolean patchFirst = context.getRequest().hasGeneratedCode();
        CodeGenTypeEnum routedType = routingSupport.routeTargetType(context.getRequest(), complex);
        context.setTargetType(CodeGenTypeEnum.max(context.getRequest().currentType(), routedType));
        context.setUpgradeRequired(context.getRequest().currentType().canUpgradeTo(context.getTargetType()));
        boolean requiresBuild = routingSupport.requiresBuildValidation(context.getRequest(), context.getTargetType());
        String generationMode = patchFirst ? "patch_first_update" : "full_generation";
        String validationMode = requiresBuild ? "build_validation" : "review_only";
        List<GenerationRecipe> matchedRecipes = support.matchRecipes(userMessage, "");
        List<GenerationSkill> matchedSkills = support.matchSkills(userMessage);
        App app = context.getRequest().app();
        GenerationAgentSupport.ProjectIndexRecall indexRecall = patchFirst
                ? support.collectProjectIndexRecall(app, userMessage, 3)
                : new GenerationAgentSupport.ProjectIndexRecall(null, List.of());
        context.setWorkspaceIndexSnapshot(indexRecall.indexSnapshot());
        List<Map<String, Object>> indexHits = indexRecall.indexHits();
        List<String> goals = new java.util.ArrayList<>(List.of(
                "保留现有项目能力并尽量复用结构",
                complex ? "按模块拆分生成任务，允许并行处理" : "采用单模块增量生成策略",
                requiresBuild ? "生成后必须经过 Review 与 BuildFix 门禁" : "生成后经过 Review 门禁，默认跳过构建修复链路",
                matchedRecipes.isEmpty() ? "未匹配到专项 recipe，按通用生成策略执行" : "套用匹配的 recipe 作为最小实现边界",
                matchedSkills.isEmpty() ? "未匹配到专项 skill，按通用生成策略执行" : "套用匹配的 skill 作为实现约束"
        ));
        if (context.getTargetType() == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            goals.add("全栈项目必须共享平台分配的前后端端口与 API 地址上下文");
            goals.add("前端必须通过 VITE_API_BASE_URL 调用后端，不硬编码 localhost 端口");
            goals.add("容器化部署本期只预留配置和上下文，不自动启动用户生成的后端服务");
        }
        if (context.getTargetType() == CodeGenTypeEnum.BACKEND_PROJECT
                || context.getTargetType() == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            goals.add("后端生成必须优先复用 Go + SQLite 模板，围绕 Model/Repository/Service/Handler/Schema 同步落地业务能力");
            goals.add("后端 Repository 必须使用参数化 SQL，Handler 使用统一响应，Service 承载业务规则和错误消息");
            goals.add("前后端字段必须先沉淀为 API 字段契约，再同步到 DTO/VO、表单列表、Repository scan 和 SQLite schema");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complex", complex);
        payload.put("targetType", context.getTargetType().getValue());
        payload.put("upgradeRequired", context.isUpgradeRequired());
        payload.put("patchFirst", patchFirst);
        payload.put("requiresBuild", requiresBuild);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("orchestrationMode", requiresBuild ? "heavy" : "light");
        payload.put("contextRecallSource", patchFirst ? "semantic_index" : "new_project");
        payload.put("contextRecallQuery", userMessage);
        payload.put("indexHits", indexHits);
        payload.put("goals", goals);
        payload.put("recipeIds", matchedRecipes.stream().map(GenerationRecipe::id).toList());
        payload.put("recipes", support.buildRecipePayloads(matchedRecipes));
        payload.put("skillIds", matchedSkills.stream().map(GenerationSkill::id).toList());
        payload.put("skills", support.buildSkillPayloads(matchedSkills));
        GenerationArtifact artifact = GenerationArtifact.of("requirements", "Planner", "需求与目标", payload);
        GenerationArtifact apiContractArtifact = GenerationArtifact.of(
                "api_contract",
                "Planner",
                "API 字段契约",
                buildApiContractPayload(context, userMessage)
        );
        return AgentNodeResult.of(
                complex ? "需求已拆解为复杂任务，准备进入模块级 DAG 生成" : "需求已拆解为标准任务，采用轻量 DAG 生成",
                List.of(artifact, apiContractArtifact),
                Map.of(
                        "complex", complex,
                        "targetType", context.getTargetType().getValue(),
                        "upgradeRequired", context.isUpgradeRequired(),
                        "patchFirst", patchFirst,
                        "requiresBuild", requiresBuild,
                        "validationMode", validationMode,
                        "generationMode", generationMode,
                        "indexHitCount", indexHits.size(),
                        "skillCount", matchedSkills.size()
                )
        );
    }

    private Map<String, Object> buildApiContractPayload(GenerationAgentContext context, String userMessage) {
        boolean frontendFirstUpgrade = context.getRequest().hasGeneratedCode()
                && context.getRequest().currentType() == CodeGenTypeEnum.VUE_PROJECT
                && context.getTargetType() == CodeGenTypeEnum.FULL_STACK_PROJECT;
        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("version", "v1");
        contract.put("apiPrefix", "/api");
        contract.put("moduleDirectory", "internal/modules/sample");
        contract.put("fieldSource", frontendFirstUpgrade ? "existing_frontend_reverse_extract" : "user_requirement_first");
        Map<String, Object> inferredDomain = inferContractDomain(userMessage);
        contract.put("moduleName", inferredDomain.get("moduleName"));
        contract.put("moduleDirectory", "internal/modules/" + inferredDomain.get("moduleName"));
        contract.put("entities", inferredDomain.get("entities"));
        contract.put("endpoints", inferredDomain.get("endpoints"));
        contract.put("schemaTables", inferredDomain.get("schemaTables"));
        contract.put("notes", List.of(
                "首阶段只生成最小契约骨架，具体字段由 CREATE spec 和本地 recipe 从用户需求中补齐",
                "从前端升级全栈时，优先从现有 API 调用、mock 数据、表单字段、表格列反推字段契约",
                "字段命名需同步覆盖 frontend DTO、backend request/response、repository scan 和 SQLite schema"
        ));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source", frontendFirstUpgrade ? "frontend_first_upgrade" : "planner");
        payload.put("userMessage", userMessage);
        payload.put("contract", contract);
        return payload;
    }

    private Map<String, Object> inferContractDomain(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "商品", "产品", "product")) {
            return domainPayload(
                    "product",
                    "Product",
                    "products",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("name", "string", "text", "名称"),
                            field("price", "float64", "real", "价格"),
                            field("description", "string", "text", "描述"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
        }
        if (containsAny(normalized, "订单", "order")) {
            return domainPayload(
                    "order",
                    "Order",
                    "orders",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("orderNo", "string", "text", "订单号"),
                            field("status", "string", "text", "状态"),
                            field("amount", "float64", "real", "金额"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
        }
        if (containsAny(normalized, "任务", "task", "todo")) {
            return domainPayload(
                    "task",
                    "Task",
                    "tasks",
                    List.of(
                            field("id", "int64", "integer", "主键"),
                            field("title", "string", "text", "标题"),
                            field("status", "string", "text", "状态"),
                            field("priority", "string", "text", "优先级"),
                            field("createdAt", "time.Time", "timestamp", "创建时间"),
                            field("updatedAt", "time.Time", "timestamp", "更新时间")
                    )
            );
        }
        return domainPayload(
                "app",
                "AppItem",
                "app_items",
                List.of(
                        field("id", "int64", "integer", "主键"),
                        field("name", "string", "text", "名称"),
                        field("status", "string", "text", "状态"),
                        field("createdAt", "time.Time", "timestamp", "创建时间"),
                        field("updatedAt", "time.Time", "timestamp", "更新时间")
                )
        );
    }

    private Map<String, Object> domainPayload(String moduleName,
                                              String entityName,
                                              String tableName,
                                              List<Map<String, String>> fields) {
        Map<String, Object> entity = new LinkedHashMap<>();
        entity.put("name", entityName);
        entity.put("fields", fields);
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("name", tableName);
        table.put("fields", fields);
        List<Map<String, String>> endpoints = List.of(
                endpoint("POST", "/api/" + moduleName, "create"),
                endpoint("PUT", "/api/" + moduleName + "/{id}", "update"),
                endpoint("GET", "/api/" + moduleName + "/{id}", "detail"),
                endpoint("POST", "/api/" + moduleName + "/list/page", "page"),
                endpoint("DELETE", "/api/" + moduleName + "/{id}", "delete")
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("moduleName", moduleName);
        payload.put("entities", List.of(entity));
        payload.put("schemaTables", List.of(table));
        payload.put("endpoints", endpoints);
        return payload;
    }

    private Map<String, String> field(String jsonName, String goType, String sqliteType, String description) {
        return Map.of(
                "jsonName", jsonName,
                "goType", goType,
                "sqliteType", sqliteType,
                "description", description
        );
    }

    private Map<String, String> endpoint(String method, String path, String action) {
        return Map.of("method", method, "path", path, "action", action);
    }

    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
