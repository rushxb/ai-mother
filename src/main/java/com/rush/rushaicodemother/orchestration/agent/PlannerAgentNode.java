package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact.ApiDomain;
import com.rush.rushaicodemother.orchestration.artifact.ApiContractArtifact.ApiField;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationRequirementsArtifact;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipe;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

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

    /**
 * 执行{@code Planner}智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return {@code Planner}智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String userMessage = StrUtil.blankToDefault(context.getRequest().userMessage(), "");
        GenerationScenarioDecision scenarioDecision = context.getRequest().scenarioDecision();
        if (scenarioDecision == null) {
            throw new IllegalStateException("Planner 必须消费准入阶段冻结的场景决策");
        }
        // 复杂度会改变 DAG 深度与生成预算，不能在 Planner 中再次解析原始 Prompt。
        boolean complex = scenarioDecision.intentProfile().semanticComplexity()
                == IntentSemanticComplexity.HIGH;
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
        GenerationRequirementsArtifact requirements = GenerationRequirementsArtifact.create(
                complex,
                context.getTargetType(),
                context.isUpgradeRequired(),
                patchFirst,
                requiresBuild,
                patchFirst ? "semantic_index" : "new_project",
                userMessage,
                indexHits,
                goals,
                support.buildRecipePayloads(matchedRecipes),
                support.buildSkillPayloads(matchedSkills)
        );
        GenerationArtifact artifact = requirements.toArtifact();
        ApiContractArtifact apiContract = ApiContractArtifact.create(
                isFrontendFirstUpgrade(context),
                userMessage,
                inferContractDomain(userMessage)
        );
        return AgentNodeResult.of(
                complex ? "需求已拆解为复杂任务，准备进入模块级 DAG 生成" : "需求已拆解为标准任务，采用轻量 DAG 生成",
                List.of(artifact, apiContract.toArtifact()),
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

    /** 是否应优先从现有前端反向提取字段契约。 */
    private boolean isFrontendFirstUpgrade(GenerationAgentContext context) {
        return context.getRequest().hasGeneratedCode()
                && context.getRequest().currentType() == CodeGenTypeEnum.VUE_PROJECT
                && context.getTargetType() == CodeGenTypeEnum.FULL_STACK_PROJECT;
    }

    /** 返回{@code infer}{@code Contract}{@code Domain}。 */
    private ApiDomain inferContractDomain(String userMessage) {
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

    /** 返回{@code domain}载荷。 */
    private ApiDomain domainPayload(String moduleName,
                                    String entityName,
                                    String tableName,
                                    List<ApiField> fields) {
        return new ApiDomain(moduleName, entityName, tableName, fields);
    }

    private ApiField field(String jsonName, String goType, String sqliteType, String description) {
        return new ApiField(jsonName, goType, sqliteType, description);
    }

    /** 返回{@code contains}{@code Any}。 */
    private boolean containsAny(String value, String... keywords) {
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
