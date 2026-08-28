package com.rush.rushaicodemother.orchestration.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 用户可查询、可重放的终态交付回执。
 *
 * <p>协议版本独立于内部终态命令版本，客户端只需按 {@link #schemaVersion()} 做向前兼容。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationDeliveryReceipt(
        int schemaVersion,
        String actualRoute,
        GenerationChangeSummary changeSummary,
        GenerationValidationSummary validationSummary,
        String previewMaturity,
        Long firstPreviewMillis,
        String failureCategory,
        boolean retryable,
        String recoveryAction,
        String nextStep,
        GenerationCostSummary costSummary
) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public GenerationDeliveryReceipt {
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("交付回执版本必须为正数");
        }
        actualRoute = requireText(actualRoute, "实际路由不能为空");
        if (changeSummary == null || validationSummary == null || costSummary == null) {
            throw new IllegalArgumentException("交付回执摘要不能为空");
        }
        previewMaturity = requireText(previewMaturity, "预览成熟度不能为空");
        if (firstPreviewMillis != null && firstPreviewMillis < 0) {
            throw new IllegalArgumentException("首次预览耗时不能为负数");
        }
        failureCategory = normalize(failureCategory);
        recoveryAction = requireText(recoveryAction, "恢复动作不能为空");
        nextStep = requireText(nextStep, "下一步不能为空");
    }

    public GenerationDeliveryReceipt withCostSummary(GenerationCostSummary latestCostSummary) {
        if (latestCostSummary == null || latestCostSummary.equals(costSummary)) {
            return this;
        }
        return new GenerationDeliveryReceipt(
                schemaVersion, actualRoute, changeSummary, validationSummary,
                previewMaturity, firstPreviewMillis, failureCategory, retryable,
                recoveryAction, nextStep, latestCostSummary);
    }

    public GenerationDeliveryReceipt withActualRoute(String fallbackRoute) {
        if (fallbackRoute == null || fallbackRoute.isBlank()
                || actualRoute.equals(fallbackRoute.trim())) {
            return this;
        }
        return new GenerationDeliveryReceipt(
                schemaVersion, fallbackRoute.trim(), changeSummary, validationSummary,
                previewMaturity, firstPreviewMillis, failureCategory, retryable,
                recoveryAction, nextStep, costSummary);
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
