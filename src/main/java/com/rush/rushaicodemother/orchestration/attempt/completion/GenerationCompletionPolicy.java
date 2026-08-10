package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 成功终态的统一完成门禁。
 *
 * <p>证据判断与实时运行时所有权判断分离：前者来自流水线结构化结果，后者在终态前
 * 重新读取持久租约，避免取消、审批或旧执行纪元被误报为成功。</p>
 */
@Component
public class GenerationCompletionPolicy {

    private final GenerationTaskFenceGuard generationTaskFenceGuard;

    public GenerationCompletionPolicy(GenerationTaskFenceGuard generationTaskFenceGuard) {
        this.generationTaskFenceGuard = Objects.requireNonNull(
                generationTaskFenceGuard, "生成任务栅栏守卫不能为空");
    }

    public GenerationCompletionDecision evaluate(
            GenerationSession session,
            GenerationExecutionPlan.ValidationGraph validationGraph,
            GenerationCompletionEvidenceSet evidenceSet
    ) {
        Objects.requireNonNull(session, "生成会话不能为空");
        GenerationCompletionEvidenceSet evidence = evidenceSet == null
                ? GenerationCompletionEvidenceSet.empty()
                : evidenceSet;
        ExpectedValidationLevel level = validationGraph == null
                ? ExpectedValidationLevel.FAST
                : validationGraph.level();
        List<GenerationCompletionRequirement> missing = new ArrayList<>();
        if (!evidence.contains(GenerationCompletionEvidenceType.INTENT_COVERAGE)) {
            missing.add(GenerationCompletionRequirement.INTENT_COVERAGE);
        }
        if (!evidence.contains(GenerationCompletionEvidenceType.WORKSPACE_CHANGE)
                && !evidence.contains(GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION)) {
            missing.add(GenerationCompletionRequirement.WORKSPACE_RESULT);
        }
        if (!evidence.contains(GenerationCompletionEvidenceType.FAST_VALIDATION)) {
            missing.add(GenerationCompletionRequirement.FAST_VALIDATION);
        }
        if ((level == ExpectedValidationLevel.BUILD || level == ExpectedValidationLevel.EXPERT)
                && !evidence.contains(GenerationCompletionEvidenceType.BUILD_VALIDATION)) {
            missing.add(GenerationCompletionRequirement.BUILD_VALIDATION);
        }
        if (level == ExpectedValidationLevel.EXPERT
                && !evidence.contains(GenerationCompletionEvidenceType.EXPERT_VALIDATION)) {
            missing.add(GenerationCompletionRequirement.EXPERT_VALIDATION);
        }
        if (!runtimeOwnershipValid(session)) {
            missing.add(GenerationCompletionRequirement.RUNTIME_OWNERSHIP);
        }
        if (missing.isEmpty()) {
            return new GenerationCompletionDecision(true, List.of(), "完成证据已满足");
        }
        String summary = "生成任务不能完成：" + missing.stream()
                .map(GenerationCompletionRequirement::message)
                .collect(Collectors.joining("；"));
        return new GenerationCompletionDecision(false, missing, summary);
    }

    public void requireCompletable(
            GenerationSession session,
            GenerationExecutionPlan.ValidationGraph validationGraph,
            GenerationCompletionEvidenceSet evidenceSet
    ) {
        GenerationCompletionDecision decision = evaluate(session, validationGraph, evidenceSet);
        if (!decision.completable()) {
            throw new GenerationCompletionEvidenceException(decision);
        }
    }

    private boolean runtimeOwnershipValid(GenerationSession session) {
        try {
            session.throwIfCancelled();
            if (session.executionContext() != null) {
                session.executionContext().assertCanContinue();
            }
            GenerationExecutionFence fence = session.executionContext() == null
                    ? null
                    : session.executionContext().executionFence();
            if (fence != null) {
                generationTaskFenceGuard.assertCurrent(fence);
            }
            return true;
        } catch (RuntimeException invalidRuntime) {
            return false;
        }
    }
}
