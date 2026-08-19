package com.rush.rushaicodemother.orchestration.economics;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 生成链路的单位成功交付经济性。
 *
 * <p>总成本包含成功和失败尝试的真实消耗，分母只使用成功交付数，避免失败率升高时
 * “平均成本”反而下降。没有成功交付时单位成本不可定义，因此使用 {@code null}
 * 明确表达缺失，而不是伪造为零。</p>
 */
public record GenerationDeliveryEconomics(
        long successfulDeliveryCount,
        Double providerTokensPerSuccessfulDelivery,
        Double creditCostPerSuccessfulDelivery
) {

    public GenerationDeliveryEconomics {
        if (successfulDeliveryCount < 0) {
            throw new IllegalArgumentException("成功交付数不能为负数");
        }
        if (successfulDeliveryCount == 0
                && (providerTokensPerSuccessfulDelivery != null
                || creditCostPerSuccessfulDelivery != null)) {
            throw new IllegalArgumentException("没有成功交付时单位成本必须为空");
        }
        if (successfulDeliveryCount > 0
                && (!validCost(providerTokensPerSuccessfulDelivery)
                || !validCost(creditCostPerSuccessfulDelivery))) {
            throw new IllegalArgumentException("单位成功交付成本必须是非负有限数");
        }
    }

    /** 从完整尝试成本和成功交付数构造唯一成本口径。 */
    public static GenerationDeliveryEconomics fromTotals(long successfulDeliveryCount,
                                                          long totalProviderTokens,
                                                          long totalCreditCost) {
        if (successfulDeliveryCount < 0 || totalProviderTokens < 0 || totalCreditCost < 0) {
            throw new IllegalArgumentException("生成成本汇总不能为负数");
        }
        if (successfulDeliveryCount == 0) {
            return new GenerationDeliveryEconomics(0, null, null);
        }
        return new GenerationDeliveryEconomics(
                successfulDeliveryCount,
                (double) totalProviderTokens / successfulDeliveryCount,
                (double) totalCreditCost / successfulDeliveryCount);
    }

    /** 单位成本是否具备真实成功交付作为分母。 */
    @JsonIgnore
    public boolean isAvailable() {
        return successfulDeliveryCount > 0;
    }

    private static boolean validCost(Double value) {
        return value != null && Double.isFinite(value) && value >= 0.0;
    }
}
