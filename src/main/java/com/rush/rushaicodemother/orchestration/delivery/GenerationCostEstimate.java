package com.rush.rushaicodemother.orchestration.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 任务提交时冻结的成本上界；实际费用由 Provider 成本账本在终态结算。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationCostEstimate(
        long estimatedCreditMin,
        long estimatedCreditMax,
        long maximumReservedCredit,
        String summary
) {

    public GenerationCostEstimate {
        if (estimatedCreditMin < 0
                || estimatedCreditMax < estimatedCreditMin
                || maximumReservedCredit < estimatedCreditMax) {
            throw new IllegalArgumentException("预计积分区间或冻结上限不合法");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("预计成本摘要不能为空");
        }
        summary = summary.trim();
    }

    /**
     * 根据真实预授权金额建立保守区间。
     *
     * <p>执行前没有 Provider 用量事实，最低费用可能为零，因此不虚构非零下界。</p>
     */
    public static GenerationCostEstimate fromMaximumReservation(long maximumReservedCredit) {
        if (maximumReservedCredit < 0) {
            throw new IllegalArgumentException("最大冻结积分不能为负数");
        }
        return new GenerationCostEstimate(
                0L,
                maximumReservedCredit,
                maximumReservedCredit,
                "预计 0 至 " + maximumReservedCredit
                        + " 积分，最多冻结 " + maximumReservedCredit + " 积分"
        );
    }
}
