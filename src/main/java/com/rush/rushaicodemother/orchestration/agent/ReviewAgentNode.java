package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateResult;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import com.rush.rushaicodemother.orchestration.review.BackendQualityReviewService;
import com.rush.rushaicodemother.orchestration.review.VueSecurityReviewService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Review：生成前质量门禁。
 */
@Component
public class ReviewAgentNode extends BaseGenerationAgentNode {

    private final VueSecurityReviewService vueSecurityReviewService;
    private final BackendQualityReviewService backendQualityReviewService;

    public ReviewAgentNode(VueSecurityReviewService vueSecurityReviewService,
                           BackendQualityReviewService backendQualityReviewService) {
        super("review", "Review", "quality", List.of("code"), GenerationNodeReplayPolicy.REPLAY_SAFE);
        this.vueSecurityReviewService = Objects.requireNonNull(
                vueSecurityReviewService,
                "vueSecurityReviewService must not be null"
        );
        this.backendQualityReviewService = Objects.requireNonNull(
                backendQualityReviewService,
                "backendQualityReviewService must not be null"
        );
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
        } else if (patchFirst) {
            blockers.addAll(changePlan.validateForPatchFirst(requiresBuild, validationMode));
            if (blockers.isEmpty()) {
                passes.add("变更计划已生成并通过契约校验");
            }
        } else if (hasChangePlan) {
            passes.add("完整生成计划已生成");
        }
        if (patchFirst) {
            passes.add("已启用 patch-first 计划型生成");
        }
        if (requiresBuild) {
            passes.add("目标模式为工程化项目，将启用 BuildFix 门禁");
        } else {
            passes.add("当前为轻量校验模式，默认跳过 BuildFix");
        }
        VueSecurityReviewService.SecurityReviewResult securityReviewResult = vueSecurityReviewService.review(prompt);
        blockers.addAll(securityReviewResult.blockers());
        warnings.addAll(securityReviewResult.warnings());
        if (securityReviewResult.passed()) {
            passes.add("Vue 安全审查未发现阻断项");
        }
        BackendQualityReviewService.BackendReviewResult backendReviewResult =
                backendQualityReviewService.review(context, prompt);
        blockers.addAll(backendReviewResult.blockers());
        warnings.addAll(backendReviewResult.warnings());
        passes.addAll(backendReviewResult.passes());
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
        payload.put("securityBlockers", securityReviewResult.blockers());
        payload.put("securityWarnings", securityReviewResult.warnings());
        payload.put("backendBlockers", backendReviewResult.blockers());
        payload.put("backendWarnings", backendReviewResult.warnings());
        GenerationArtifact artifact = GenerationArtifact.of("quality_gate", "Review", "质量门禁", payload);
        return AgentNodeResult.of(
                gateResult.passed() ? "质量门禁通过，允许执行代码生成" : "质量门禁未通过，阻止后续生成",
                List.of(artifact),
                payload
        );
    }
}
