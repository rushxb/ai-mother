package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingDecisionCode;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 基于结构化意图画像生成候选路由，仅供影子评估使用。 */
@Component
public class IntentProfileRoutingDecisionEngine implements GenerationShadowRouteChallenger {

    @Override
    public GenerationModeDecision decide(IntentProfile intentProfile) {
        IntentProfile profile = Objects.requireNonNull(intentProfile, "意图画像不能为空");
        if (profile.operationType() == IntentOperationType.CREATE) {
            return decideCreate(profile);
        }
        if (isReadOnly(profile.operationType())) {
            return GenerationModeDecision.of(
                    GenerationMode.READ_ONLY,
                    profile.confidence(),
                    "结构化意图明确要求只读分析，不允许修改或发布工作区",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.FAST,
                    GenerationRoutingDecisionCode.INTENT_PROFILE_READ_ONLY
            );
        }
        if (isHeavyExistingWorkspaceChange(profile)) {
            return GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    profile.confidence(),
                    "结构化意图表明现有项目存在高复杂度或高破坏性改造风险",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.EXPERT,
                    GenerationRoutingDecisionCode.INTENT_PROFILE_HEAVY_EDIT
            );
        }
        if (isLightweightEdit(profile)) {
            return GenerationModeDecision.of(
                    GenerationMode.LIGHT_EDIT,
                    profile.confidence(),
                    "结构化意图表明修改范围小且无需后端或数据库变更",
                    FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                    ExpectedValidationLevel.FAST,
                    GenerationRoutingDecisionCode.INTENT_PROFILE_LIGHT_EDIT
            );
        }
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                profile.confidence(),
                "结构化意图需要代码理解、跨文件处理或中高强度验证",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                expectedValidationLevel(profile),
                GenerationRoutingDecisionCode.INTENT_PROFILE_AGENT_EDIT
        );
    }

    private GenerationModeDecision decideCreate(IntentProfile profile) {
        if (profile.semanticComplexity() == IntentSemanticComplexity.HIGH
                || profile.destructiveRisk() == IntentDestructiveRisk.HIGH
                || profile.affectedScopes().contains(IntentAffectedScope.INFRASTRUCTURE)) {
            return GenerationModeDecision.of(
                    GenerationMode.HEAVY_EXPERT,
                    profile.confidence(),
                    "结构化意图超出模板优先创建路径的稳妥覆盖范围",
                    FallbackPolicy.NONE,
                    ExpectedValidationLevel.EXPERT,
                    GenerationRoutingDecisionCode.INTENT_PROFILE_COMPLEX_CREATE
            );
        }
        return GenerationModeDecision.of(
                GenerationMode.CREATE,
                profile.confidence(),
                "结构化意图适合采用模板优先的首次创建路径",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                expectedValidationLevel(profile),
                GenerationRoutingDecisionCode.INTENT_PROFILE_CREATE
        );
    }

    private boolean isHeavyExistingWorkspaceChange(IntentProfile profile) {
        return profile.destructiveRisk() == IntentDestructiveRisk.HIGH
                || profile.affectedScopes().contains(IntentAffectedScope.INFRASTRUCTURE)
                || (profile.semanticComplexity() == IntentSemanticComplexity.HIGH
                && profile.expectedFileCount() >= 8);
    }

    private boolean isLightweightEdit(IntentProfile profile) {
        return profile.operationType() == IntentOperationType.EDIT
                && profile.semanticComplexity() == IntentSemanticComplexity.LOW
                && profile.expectedFileCount() <= 2
                && !profile.requiresBackend()
                && !profile.requiresDatabase()
                && profile.destructiveRisk() == IntentDestructiveRisk.LOW
                && profile.validationRisk() == IntentValidationRisk.LOW;
    }

    private boolean isReadOnly(IntentOperationType operationType) {
        return operationType == IntentOperationType.EXPLAIN
                || operationType == IntentOperationType.AUDIT
                || operationType == IntentOperationType.PLAN;
    }

    private ExpectedValidationLevel expectedValidationLevel(IntentProfile profile) {
        return switch (profile.validationRisk()) {
            case LOW -> ExpectedValidationLevel.FAST;
            case MEDIUM -> ExpectedValidationLevel.BUILD;
            case HIGH -> ExpectedValidationLevel.EXPERT;
        };
    }
}
