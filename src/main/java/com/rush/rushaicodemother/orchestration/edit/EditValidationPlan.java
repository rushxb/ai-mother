package com.rush.rushaicodemother.orchestration.edit;

import java.util.List;

/**
 * 编辑验证计划。
 * 根据 patch 操作和变更文件决定验证级别。
 */
public record EditValidationPlan(
        ValidationLevel level,
        String reason,
        List<String> changedFiles,
        boolean aiSuggestedBuild
) {

    public enum ValidationLevel {
        /**
         * 无需验证，例如纯文案修改
         */
        NONE,
        /**
         * 快速检查，例如语法检查、格式检查
         */
        FAST_CHECK,
        /**
         * 需要构建验证
         */
        BUILD_REQUIRED,
        /**
         * 需要完整审查（构建 + 代码审查）
         */
        HEAVY_REVIEW_REQUIRED
    }

    /**
     * 是否需要后台验证
     */
    public boolean requiresBackgroundValidation() {
        return level != ValidationLevel.NONE;
    }

    /**
     * 是否需要构建
     */
    public boolean requiresBuild() {
        return level == ValidationLevel.BUILD_REQUIRED || level == ValidationLevel.HEAVY_REVIEW_REQUIRED;
    }

    /**
     * 是否需要完整审查
     */
    public boolean requiresHeavyReview() {
        return level == ValidationLevel.HEAVY_REVIEW_REQUIRED;
    }
}
