package com.yupi.yuaicodemother.orchestration.agent;

import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.artifact.QualityGateResult;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Review：生成前质量门禁。
 */
@Component
public class ReviewAgentNode extends BaseGenerationAgentNode {

    public ReviewAgentNode() {
        super("review", "Review", "quality", List.of("code"));
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        Object promptObj = context.getArtifactValue("generation_spec", "enhancedPrompt");
        String prompt = promptObj == null ? "" : String.valueOf(promptObj);
        boolean patchFirst = artifactBooleanValue(context, "generation_spec", "patchFirst");
        boolean requiresBuild = artifactBooleanValue(context, "generation_spec", "requiresBuild");
        String validationMode = artifactStringValue(context, "generation_spec", "validationMode",
                requiresBuild ? "build_validation" : "review_only");
        String generationMode = artifactStringValue(context, "generation_spec", "generationMode",
                patchFirst ? "patch_first_update" : "full_generation");
        ChangePlan changePlan = context.getArtifact("change_plan")
                .map(GenerationArtifact::payload)
                .map(ChangePlan::fromPayload)
                .orElse(null);
        boolean hasChangePlan = changePlan != null;
        if (prompt.isBlank()) {
            blockers.add("生成规范为空，无法进入代码生成");
        } else {
            passes.add("生成规范已构建");
        }
        if (patchFirst && !hasChangePlan) {
            blockers.add("缺少标准化变更计划，无法执行 patch-first 生成");
        } else if (hasChangePlan) {
            blockers.addAll(changePlan.validateForPatchFirst(requiresBuild, validationMode));
            if (blockers.isEmpty()) {
                passes.add("变更计划已生成并通过契约校验");
            }
        }
        if (patchFirst) {
            passes.add("已启用 patch-first 计划型生成");
        }
        if (requiresBuild) {
            passes.add("目标模式为工程化项目，将启用 BuildFix 门禁");
        } else {
            passes.add("当前为轻量校验模式，默认跳过 BuildFix");
        }
        QualityGateResult gateResult = blockers.isEmpty()
                ? QualityGateResult.passed(warnings, passes)
                : QualityGateResult.failed(blockers, warnings, passes);
        context.setQualityGateResult(gateResult);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("passed", gateResult.passed());
        payload.put("level", gateResult.level());
        payload.put("blockers", gateResult.blockers());
        payload.put("warnings", gateResult.warnings());
        payload.put("passes", gateResult.passes());
        payload.put("patchFirst", patchFirst);
        payload.put("requiresBuild", requiresBuild);
        payload.put("validationMode", validationMode);
        payload.put("generationMode", generationMode);
        payload.put("hasChangePlan", hasChangePlan);
        GenerationArtifact artifact = GenerationArtifact.of("quality_gate", "Review", "质量门禁", payload);
        return AgentNodeResult.of(
                gateResult.passed() ? "质量门禁通过，允许执行代码生成" : "质量门禁未通过，阻止后续生成",
                List.of(artifact),
                payload
        );
    }
}
