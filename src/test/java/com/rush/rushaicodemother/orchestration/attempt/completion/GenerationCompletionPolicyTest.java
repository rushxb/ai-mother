package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class GenerationCompletionPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

    /**
     * 构造只放行栅栏校验的完成门禁。
     *
     * <p>栅栏守卫是构造期强依赖，测试注入放行 mock，使断言聚焦于证据判定本身；
     * 栅栏拒绝路径由 {@code staleExecutionFenceMustFailClosed} 单独覆盖。</p>
     */
    private static GenerationCompletionPolicy policyWithPassingFence() {
        return new GenerationCompletionPolicy(mock(GenerationTaskFenceGuard.class));
    }

    @Test
    void completeFastEvidenceMustAllowSuccess() {
        GenerationCompletionPolicy policy = policyWithPassingFence();

        assertDoesNotThrow(() -> policy.requireCompletable(
                sessionWithoutFence(),
                graph(ExpectedValidationLevel.FAST),
                evidence(ExpectedValidationLevel.FAST)
        ));
    }

    @Test
    void missingIntentEvidenceMustFailClosed() {
        GenerationCompletionEvidenceSet evidence = GenerationCompletionEvidenceSet.of(
                item(GenerationCompletionEvidenceType.WORKSPACE_CHANGE),
                item(GenerationCompletionEvidenceType.FAST_VALIDATION)
        );

        GenerationCompletionDecision decision = policyWithPassingFence().evaluate(
                sessionWithoutFence(), graph(ExpectedValidationLevel.FAST), evidence);

        assertFalse(decision.completable());
        assertTrue(decision.missing().contains(GenerationCompletionRequirement.INTENT_COVERAGE));
    }

    @Test
    void structuredNoChangeJustificationMustSatisfyWorkspaceRequirement() {
        GenerationCompletionEvidenceSet evidence = GenerationCompletionEvidenceSet.of(
                item(GenerationCompletionEvidenceType.INTENT_COVERAGE),
                item(GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION),
                item(GenerationCompletionEvidenceType.FAST_VALIDATION)
        );

        assertTrue(policyWithPassingFence().evaluate(
                sessionWithoutFence(), graph(ExpectedValidationLevel.FAST), evidence).completable());
    }

    @Test
    void buildGraphMustRequireBuildEvidence() {
        GenerationCompletionDecision decision = policyWithPassingFence().evaluate(
                sessionWithoutFence(),
                graph(ExpectedValidationLevel.BUILD),
                evidence(ExpectedValidationLevel.FAST)
        );

        assertTrue(decision.missing().contains(GenerationCompletionRequirement.BUILD_VALIDATION));
    }

    @Test
    void expertGraphMustRequireExpertEvidence() {
        GenerationCompletionDecision decision = policyWithPassingFence().evaluate(
                sessionWithoutFence(),
                graph(ExpectedValidationLevel.EXPERT),
                evidence(ExpectedValidationLevel.BUILD)
        );

        assertTrue(decision.missing().contains(GenerationCompletionRequirement.EXPERT_VALIDATION));
    }

    @Test
    void cancelledSessionMustFailRuntimeOwnershipCheck() {
        GenerationSession session = sessionWithoutFence();
        session.cancel("测试取消");

        GenerationCompletionDecision decision = policyWithPassingFence().evaluate(
                session, graph(ExpectedValidationLevel.FAST), evidence(ExpectedValidationLevel.FAST));

        assertTrue(decision.missing().contains(GenerationCompletionRequirement.RUNTIME_OWNERSHIP));
    }

    @Test
    void stalePersistentFenceMustFailRuntimeOwnershipCheck() {
        GenerationTaskFenceGuard fenceGuard = mock(GenerationTaskFenceGuard.class);
        doThrow(new GenerationExecutionPolicyException("测试租约冲突"))
                .when(fenceGuard).assertCurrent(any(GenerationExecutionFence.class));
        GenerationSession session = new GenerationSession(null, executionContextWithFence());

        GenerationCompletionDecision decision = new GenerationCompletionPolicy(fenceGuard).evaluate(
                session, graph(ExpectedValidationLevel.FAST), evidence(ExpectedValidationLevel.FAST));

        assertTrue(decision.missing().contains(GenerationCompletionRequirement.RUNTIME_OWNERSHIP));
        assertThrows(GenerationCompletionEvidenceException.class, () ->
                new GenerationCompletionPolicy(fenceGuard).requireCompletable(
                        session, graph(ExpectedValidationLevel.FAST), evidence(ExpectedValidationLevel.FAST)));
    }

    private GenerationSession sessionWithoutFence() {
        return new GenerationSession(null);
    }

    private GenerationExecutionContext executionContextWithFence() {
        GenerationExecutionContext context = new GenerationExecutionContext(
                "completion-policy-test", 1L, 2L, NOW,
                new GenerationRuntimeProperties().toLimits(), Clock.fixed(NOW, ZoneOffset.UTC));
        context.bindExecutionFence(new GenerationExecutionFence(
                "completion-policy-test", "test-worker", 1L));
        return context;
    }

    private GenerationExecutionPlan.ValidationGraph graph(ExpectedValidationLevel level) {
        return GenerationExecutionPlan.ValidationGraph.forLevel(level);
    }

    private GenerationCompletionEvidenceSet evidence(ExpectedValidationLevel level) {
        EnumSet<GenerationExecutionPlan.ValidationStep> steps =
                EnumSet.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK);
        if (level == ExpectedValidationLevel.BUILD || level == ExpectedValidationLevel.EXPERT) {
            steps.add(GenerationExecutionPlan.ValidationStep.BUILD);
        }
        if (level == ExpectedValidationLevel.EXPERT) {
            steps.add(GenerationExecutionPlan.ValidationStep.EXPERT_CHECK);
        }
        return ObservedValidationCompletionEvidenceFactory.forCompletedMutation(
                1,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "completion_policy_test",
                        steps,
                        Map.of())
        );
    }

    private GenerationCompletionEvidence item(GenerationCompletionEvidenceType type) {
        return GenerationCompletionEvidence.of(type, "completion_policy_test", "测试证据");
    }
}
