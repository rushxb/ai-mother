package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.recipe.GenerationRecipe;
import com.yupi.yuaicodemother.orchestration.skill.GenerationSkill;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Planner：需求拆解与路由策略。
 */
@Component
public class PlannerAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;
    private final GenerationRoutingSupport routingSupport;

    public PlannerAgentNode(GenerationAgentSupport support, GenerationRoutingSupport routingSupport) {
        super("planner", "Planner", "planning", List.of());
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
        List<Map<String, Object>> indexHits = patchFirst
                ? support.collectIndexRecallPayloads(app, userMessage, 3)
                : List.of();
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
        return AgentNodeResult.of(
                complex ? "需求已拆解为复杂任务，准备进入模块级 DAG 生成" : "需求已拆解为标准任务，采用轻量 DAG 生成",
                List.of(artifact),
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
}
