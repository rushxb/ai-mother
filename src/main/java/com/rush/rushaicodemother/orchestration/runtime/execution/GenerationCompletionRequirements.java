package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 模型阶段之后必须完成的验证能力集合。
 *
 * <p>该值在任务恢复时由冻结验证图解析，并随执行限制进入审批检查点，
 * 从而避免模型、构建和修复阶段各自按工程类型重复猜测完成窗口。</p>
 */
public record GenerationCompletionRequirements(
        boolean buildRequired,
        boolean runtimeValidationRequired
) {

    private static final Set<CodeGenTypeEnum> BUILD_CAPABLE_TYPES = EnumSet.of(
            CodeGenTypeEnum.VUE_PROJECT,
            CodeGenTypeEnum.BACKEND_PROJECT,
            CodeGenTypeEnum.FULL_STACK_PROJECT
    );

    public GenerationCompletionRequirements {
        if (runtimeValidationRequired && !buildRequired) {
            throw new IllegalArgumentException("运行时验证必须建立在构建验证之上");
        }
    }

    /** 无需构建与运行时验证。 */
    public static GenerationCompletionRequirements none() {
        return new GenerationCompletionRequirements(false, false);
    }

    /** 只需要构建验证。 */
    public static GenerationCompletionRequirements buildOnly() {
        return new GenerationCompletionRequirements(true, false);
    }

    /** 同时需要构建与运行时验证。 */
    public static GenerationCompletionRequirements buildAndRuntime() {
        return new GenerationCompletionRequirements(true, true);
    }

    /**
     * 合并冻结验证图的最低门槛与准备阶段仍可能识别出的工程构建风险。
     *
     * <p>Vue、Backend 与 FullStack 即使被冻结为 FAST，准备阶段仍可升级构建；
     * 因而模型阶段必须保留构建窗口。运行时验证则只由冻结 EXPERT 图启用。</p>
     */
    public static GenerationCompletionRequirements planned(
            CodeGenTypeEnum targetType,
            boolean validationGraphRequiresBuild,
            boolean validationGraphRequiresRuntime
    ) {
        Objects.requireNonNull(targetType, "计划目标工程类型不能为空");
        boolean buildRequired = validationGraphRequiresBuild
                || validationGraphRequiresRuntime
                || BUILD_CAPABLE_TYPES.contains(targetType);
        return new GenerationCompletionRequirements(
                buildRequired,
                validationGraphRequiresRuntime
        );
    }

    /**
     * 为没有冻结验证图的旧任务恢复保守完成需求。
     *
     * <p>旧 Heavy 验证策略会在构建成功后执行运行时验证，因此可构建工程必须
     * 同时预留两个窗口；静态 HTML 与多文件任务保持原有无构建行为。</p>
     */
    public static GenerationCompletionRequirements legacy(CodeGenTypeEnum targetType) {
        Objects.requireNonNull(targetType, "旧任务目标工程类型不能为空");
        return BUILD_CAPABLE_TYPES.contains(targetType) ? buildAndRuntime() : none();
    }
}
