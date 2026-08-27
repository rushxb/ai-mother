package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** 将不同编辑 Validator 的成功结果转换为其真实执行步骤，不读取路由期望。 */
public final class EditValidationObservationFactory {

    private EditValidationObservationFactory() {
    }

    /** BackgroundValidationService 会按计划分支实际执行 FAST、BUILD 或 EXPERT。 */
    public static Optional<GenerationValidationObservation> fromBackgroundValidator(
            GenerationWorkspace workspace,
            EditValidationPlan performedPlan,
            BackgroundValidationService.ValidationResult result,
            String source) {
        if (!successful(workspace, performedPlan, result)
                || performedPlan.level() == EditValidationPlan.ValidationLevel.NONE) {
            return Optional.empty();
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps =
                EnumSet.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK);
        if (performedPlan.requiresBuild()) {
            passedSteps.add(GenerationExecutionPlan.ValidationStep.BUILD);
        }
        if (performedPlan.requiresHeavyReview()) {
            passedSteps.add(GenerationExecutionPlan.ValidationStep.EXPERT_CHECK);
        }
        return Optional.of(observation(
                workspace.codeGenType(), source, passedSteps, performedPlan, result));
    }

    /** 后端专用 Validator 只执行静态快速检查和可选 Go 构建，不产生专家审查事实。 */
    public static Optional<GenerationValidationObservation> fromBackendValidator(
            GenerationWorkspace workspace,
            EditValidationPlan performedPlan,
            BackgroundValidationService.ValidationResult result,
            String source) {
        if (!successful(workspace, performedPlan, result)) {
            return Optional.empty();
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps =
                EnumSet.of(GenerationExecutionPlan.ValidationStep.FAST_CHECK);
        if (performedPlan.requiresBuild()) {
            passedSteps.add(GenerationExecutionPlan.ValidationStep.BUILD);
        }
        return Optional.of(observation(
                workspace.codeGenType(), source, passedSteps, performedPlan, result));
    }

    /** 合并同一目标上多个 Validator 的实际通过步骤。 */
    public static Optional<GenerationValidationObservation> merge(
            CodeGenTypeEnum targetType,
            String source,
            Collection<GenerationValidationObservation> observations) {
        if (targetType == null || observations == null || observations.isEmpty()) {
            return Optional.empty();
        }
        EnumSet<GenerationExecutionPlan.ValidationStep> steps =
                EnumSet.noneOf(GenerationExecutionPlan.ValidationStep.class);
        int observationCount = 0;
        for (GenerationValidationObservation observation : observations) {
            if (observation == null || observation.targetType() != targetType) {
                continue;
            }
            steps.addAll(observation.passedSteps());
            observationCount++;
        }
        if (steps.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(GenerationValidationObservation.passed(
                targetType,
                source,
                steps,
                Map.of("validatorObservationCount", observationCount)
        ));
    }

    private static GenerationValidationObservation observation(
            CodeGenTypeEnum targetType,
            String source,
            EnumSet<GenerationExecutionPlan.ValidationStep> passedSteps,
            EditValidationPlan performedPlan,
            BackgroundValidationService.ValidationResult result) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("performedEditValidationLevel", performedPlan.level().name());
        details.put("validationStatus", result.status());
        return GenerationValidationObservation.passed(
                targetType, source, passedSteps, details);
    }

    private static boolean successful(
            GenerationWorkspace workspace,
            EditValidationPlan performedPlan,
            BackgroundValidationService.ValidationResult result) {
        return workspace != null
                && workspace.codeGenType() != null
                && performedPlan != null
                && result != null
                && result.isSuccess();
    }
}
