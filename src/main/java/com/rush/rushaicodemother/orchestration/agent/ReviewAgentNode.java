package com.rush.rushaicodemother.orchestration.agent;

import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationSpecificationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.QualityGateArtifact;
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
import java.util.Set;

/**
 * Review：生成前质量门禁。
 */
@Component
public class ReviewAgentNode extends BaseGenerationAgentNode {

    private final VueSecurityReviewService vueSecurityReviewService;
    private final BackendQualityReviewService backendQualityReviewService;

    /**
 * 创建{@code Review}智能体节点实例并完成必要的依赖和初始状态设置。
 *
 * @param vueSecurityReviewService 处理该职责的领域服务
 * @param backendQualityReviewService 处理该职责的领域服务
 */
    public ReviewAgentNode(VueSecurityReviewService vueSecurityReviewService,
                           BackendQualityReviewService backendQualityReviewService) {
        super(
                "review",
                "Review",
                "quality",
                List.of("code"),
                GenerationNodeReplayPolicy.REPLAY_SAFE,
                Set.of(QualityGateArtifact.KEY)
        );
        this.vueSecurityReviewService = Objects.requireNonNull(
                vueSecurityReviewService,
                "vueSecurityReviewService must not be null"
        );
        this.backendQualityReviewService = Objects.requireNonNull(
                backendQualityReviewService,
                "backendQualityReviewService must not be null"
        );
    }

    /**
 * 执行{@code Review}智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return {@code Review}智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        GenerationSpecificationArtifact specification = context
                .getArtifact(GenerationSpecificationArtifact.KEY)
                .map(GenerationSpecificationArtifact::fromArtifact)
                .orElseThrow(() -> new IllegalArgumentException("缺少生成规范制品"));
        String prompt = specification.enhancedPrompt();
        boolean patchFirst = specification.patchFirst();
        boolean requiresBuild = specification.requiresBuild();
        String validationMode = specification.validationMode();
        String generationMode = specification.generationMode();
        ChangePlan changePlan = context.getArtifact(ChangePlan.KEY)
                .map(ChangePlan::fromArtifact)
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
            blockers.addAll(changePlan.validateAgainst(specification));
            if (blockers.isEmpty()) {
                passes.add("变更计划已生成并通过契约校验");
            }
        } else if (hasChangePlan) {
            blockers.addAll(changePlan.validateAgainst(specification));
            if (blockers.isEmpty()) {
                passes.add("完整生成计划已生成并通过契约校验");
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
        Map<String, Object> reviewDetails = new LinkedHashMap<>();
        reviewDetails.put("patchFirst", patchFirst);
        reviewDetails.put("requiresBuild", requiresBuild);
        reviewDetails.put("validationMode", validationMode);
        reviewDetails.put("generationMode", generationMode);
        reviewDetails.put("hasChangePlan", hasChangePlan);
        reviewDetails.put("securityBlockers", securityReviewResult.blockers());
        reviewDetails.put("securityWarnings", securityReviewResult.warnings());
        reviewDetails.put("backendBlockers", backendReviewResult.blockers());
        reviewDetails.put("backendWarnings", backendReviewResult.warnings());
        QualityGateArtifact.ReviewSubject reviewSubject = QualityGateArtifact.reviewSubject(
                context.getTargetType(),
                context.getArtifacts()
        );
        GenerationArtifact artifact = QualityGateArtifact
                .fromResult(gateResult, reviewSubject, reviewDetails)
                .toArtifact("Review", "质量门禁");
        return AgentNodeResult.of(
                gateResult.passed() ? "质量门禁通过，允许执行代码生成" : "质量门禁未通过，阻止后续生成",
                List.of(artifact),
                artifact.payload()
        );
    }
}
