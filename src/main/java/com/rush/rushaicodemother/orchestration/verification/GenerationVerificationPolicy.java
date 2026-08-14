package com.rush.rushaicodemother.orchestration.verification;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.edit.EditValidationPlan;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;

import java.util.Objects;

/**
 * 将任务提交时冻结的验证图适配为各生成链路可消费的验证门槛。
 *
 * <p>冻结计划定义最低验证级别，准备阶段识别出的项目风险仍可安全升级构建验证；
 * 旧任务没有执行计划时继续保留原有 Heavy 验证行为。</p>
 */
public record GenerationVerificationPolicy(
        GenerationExecutionPlan.ValidationGraph validationGraph,
        boolean frozenPlan
) {

    public GenerationVerificationPolicy {
        Objects.requireNonNull(validationGraph, "验证策略图不能为空");
    }

    /** 根据执行计划解析验证策略；旧任务使用调用方提供的路由级别作为兼容门槛。 */
    public static GenerationVerificationPolicy resolve(
            GenerationExecutionPlan executionPlan,
            ExpectedValidationLevel legacyValidationLevel
    ) {
        if (executionPlan != null) {
            return planned(executionPlan.validationGraph());
        }
        return legacy(legacyValidationLevel);
    }

    /** 创建由冻结执行计划驱动的验证策略。 */
    public static GenerationVerificationPolicy planned(
            GenerationExecutionPlan.ValidationGraph validationGraph
    ) {
        return new GenerationVerificationPolicy(validationGraph, true);
    }

    /** 创建旧任务兼容策略，默认不额外抬高准备阶段已经确定的构建门槛。 */
    public static GenerationVerificationPolicy legacy() {
        return legacy(ExpectedValidationLevel.FAST);
    }

    /** 创建带路由最低级别的旧任务兼容策略。 */
    public static GenerationVerificationPolicy legacy(ExpectedValidationLevel validationLevel) {
        ExpectedValidationLevel resolvedLevel = validationLevel == null
                ? ExpectedValidationLevel.FAST
                : validationLevel;
        return new GenerationVerificationPolicy(
                GenerationExecutionPlan.ValidationGraph.forLevel(resolvedLevel),
                false
        );
    }

    public ExpectedValidationLevel minimumLevel() {
        return validationGraph.level();
    }

    public boolean requiresFastCheck() {
        return contains(GenerationExecutionPlan.ValidationStep.FAST_CHECK);
    }

    public boolean requiresBuild() {
        return contains(GenerationExecutionPlan.ValidationStep.BUILD);
    }

    public boolean requiresExpertCheck() {
        return contains(GenerationExecutionPlan.ValidationStep.EXPERT_CHECK);
    }

    /**
     * 判断 Heavy 链路是否需要构建。
     * 冻结计划只定义最低门槛，因此准备阶段识别出的更高风险不能被计划降级。
     */
    public boolean requiresBuildValidation(GenerationPreparation preparation) {
        Objects.requireNonNull(preparation, "生成准备不能为空");
        if (!frozenPlan) {
            return preparation.requiresBuildValidation();
        }
        return requiresBuild() || preparation.requiresBuildValidation();
    }

    /** EXPERT 计划对所有工程类型执行真实运行时验证；简单静态类型不启动运行时。 */
    public boolean requiresRuntimeValidation(CodeGenTypeEnum targetType) {
        boolean runtimeSupported = targetType == CodeGenTypeEnum.VUE_PROJECT
                || targetType == CodeGenTypeEnum.BACKEND_PROJECT
                || targetType == CodeGenTypeEnum.FULL_STACK_PROJECT;
        return runtimeSupported && (!frozenPlan || requiresExpertCheck());
    }

    /** 将编辑链路的动态验证结果提升到冻结计划声明的最低门槛。 */
    public EditValidationPlan enforceEditMinimum(EditValidationPlan validationPlan) {
        Objects.requireNonNull(validationPlan, "编辑验证计划不能为空");
        if (!frozenPlan) {
            return validationPlan;
        }
        EditValidationPlan.ValidationLevel minimumEditLevel = switch (minimumLevel()) {
            case FAST -> EditValidationPlan.ValidationLevel.FAST_CHECK;
            case BUILD -> EditValidationPlan.ValidationLevel.BUILD_REQUIRED;
            case EXPERT -> EditValidationPlan.ValidationLevel.HEAVY_REVIEW_REQUIRED;
        };
        if (editLevelRank(validationPlan.level()) >= editLevelRank(minimumEditLevel)) {
            return validationPlan;
        }
        String floorReason = "执行计划最低验证门槛: " + minimumLevel().name();
        String reason = validationPlan.reason().isBlank()
                ? floorReason
                : validationPlan.reason() + "；" + floorReason;
        return new EditValidationPlan(
                minimumEditLevel,
                reason,
                validationPlan.changedFiles(),
                validationPlan.aiSuggestedBuild()
        );
    }
    /** 将验证图声明的最低级别写入可恢复的生成准备。 */
    public GenerationPreparation enforceValidationFloor(GenerationPreparation preparation) {
        Objects.requireNonNull(preparation, "生成准备不能为空");
        return preparation.enforceValidationFloor(minimumLevel());
    }

    private int editLevelRank(EditValidationPlan.ValidationLevel level) {
        return switch (level) {
            case NONE -> 0;
            case FAST_CHECK -> 1;
            case BUILD_REQUIRED -> 2;
            case HEAVY_REVIEW_REQUIRED -> 3;
        };
    }
    private boolean contains(GenerationExecutionPlan.ValidationStep step) {
        return validationGraph.steps().contains(step);
    }
}
