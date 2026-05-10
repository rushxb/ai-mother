package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
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

    public PlannerAgentNode(GenerationAgentSupport support) {
        super("planner", "Planner", "planning", List.of());
        this.support = support;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        String userMessage = StrUtil.blankToDefault(context.getRequest().userMessage(), "");
        boolean complex = support.isComplexRequest(userMessage);
        boolean patchFirst = context.getRequest().hasGeneratedCode();
        CodeGenTypeEnum routedType = routeTargetType(context, complex);
        context.setTargetType(CodeGenTypeEnum.max(context.getRequest().currentType(), routedType));
        context.setUpgradeRequired(context.getRequest().currentType().canUpgradeTo(context.getTargetType()));
        boolean requiresBuild = requiresBuildValidation(context, userMessage);
        String generationMode = patchFirst ? "patch_first_update" : "full_generation";
        String validationMode = requiresBuild ? "build_validation" : "review_only";
        List<String> goals = List.of(
                "保留现有项目能力并尽量复用结构",
                complex ? "按模块拆分生成任务，允许并行处理" : "采用单模块增量生成策略",
                requiresBuild ? "生成后必须经过 Review 与 BuildFix 门禁" : "生成后经过 Review 门禁，默认跳过构建修复链路"
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("complex", complex);
        payload.put("targetType", context.getTargetType().getValue());
        payload.put("upgradeRequired", context.isUpgradeRequired());
        payload.put("patchFirst", patchFirst);
        payload.put("requiresBuild", requiresBuild);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("orchestrationMode", requiresBuild ? "heavy" : "light");
        payload.put("goals", goals);
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
                        "generationMode", generationMode
                )
        );
    }

    private boolean requiresBuildValidation(GenerationAgentContext context, String userMessage) {
        if (context.getTargetType() != CodeGenTypeEnum.VUE_PROJECT) {
            return false;
        }
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (!context.getRequest().hasGeneratedCode() || context.isUpgradeRequired()) {
            return true;
        }
        return List.of("build", "构建", "打包", "编译", "测试", "lint", "校验", "发布", "npm", "vite", "工程化", "vue工程")
                .stream()
                .anyMatch(normalized::contains);
    }

    private CodeGenTypeEnum routeTargetType(GenerationAgentContext context, boolean complex) {
        if (!complex && context.getRequest().currentType() == CodeGenTypeEnum.HTML) {
            return CodeGenTypeEnum.HTML;
        }
        if (context.getRequest().currentType() == CodeGenTypeEnum.VUE_PROJECT) {
            return CodeGenTypeEnum.VUE_PROJECT;
        }
        if (context.getRequest().routingFunction() == null) {
            return complex ? CodeGenTypeEnum.VUE_PROJECT : context.getRequest().currentType();
        }
        try {
            String routingPrompt = "请根据以下需求判断最适合的生成模式：\n" + context.getRequest().userMessage();
            CodeGenTypeEnum routedType = context.getRequest().routingFunction().apply(routingPrompt);
            if (routedType != null) {
                return routedType;
            }
        } catch (Exception ignored) {
        }
        return complex ? CodeGenTypeEnum.VUE_PROJECT : context.getRequest().currentType();
    }
}
