package com.rush.rushaicodemother.orchestration.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 任务成本结算摘要；终态提交与账本结算之间显式保留 pending 状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationCostSummary(
        String settlementStatus,
        Long totalTokens,
        Long creditCost,
        Boolean charged,
        Long maximumReservedCredit,
        Long providerObservedTokens,
        Long provisionalCreditCost,
        Long refundedCredit,
        String refundReason,
        Long waivedTokens,
        String waiverReason,
        String summary
) {

    public GenerationCostSummary {
        if (settlementStatus == null || settlementStatus.isBlank()) {
            throw new IllegalArgumentException("成本结算状态不能为空");
        }
        settlementStatus = settlementStatus.trim();
        if (totalTokens != null && totalTokens < 0) {
            throw new IllegalArgumentException("Token 数不能为负数");
        }
        if (creditCost != null && creditCost < 0) {
            throw new IllegalArgumentException("积分成本不能为负数");
        }
        requireNonNegative(maximumReservedCredit, "最大冻结积分不能为负数");
        requireNonNegative(providerObservedTokens, "Provider Token 数不能为负数");
        requireNonNegative(provisionalCreditCost, "暂估积分成本不能为负数");
        requireNonNegative(refundedCredit, "退还积分不能为负数");
        requireNonNegative(waivedTokens, "免除 Token 数不能为负数");
        refundReason = normalize(refundReason);
        waiverReason = normalize(waiverReason);
        if (refundedCredit != null && maximumReservedCredit != null
                && refundedCredit > maximumReservedCredit) {
            throw new IllegalArgumentException("退还积分不能超过最大冻结积分");
        }
        if (refundedCredit != null && refundedCredit > 0 && refundReason == null) {
            throw new IllegalArgumentException("发生积分退还时必须提供原因");
        }
        if ((refundedCredit == null || refundedCredit == 0) && refundReason != null) {
            throw new IllegalArgumentException("未退还积分时不能提供退还原因");
        }
        if (waivedTokens != null && waivedTokens > 0 && waiverReason == null) {
            throw new IllegalArgumentException("发生费用免除时必须提供原因");
        }
        if ((waivedTokens == null || waivedTokens == 0) && waiverReason != null) {
            throw new IllegalArgumentException("未免除费用时不能提供免除原因");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("成本摘要不能为空");
        }
        summary = summary.trim();
    }

    /** 兼容旧调用方的五字段构造入口。 */
    public GenerationCostSummary(String settlementStatus,
                                 Long totalTokens,
                                 Long creditCost,
                                 Boolean charged,
                                 String summary) {
        this(settlementStatus, totalTokens, creditCost, charged,
                null, null, null, null, null, null, null, summary);
    }

    public static GenerationCostSummary pending() {
        return new GenerationCostSummary(
                "pending", null, null, null,
                null, null, null, null, null, null, null,
                "成本正在结算，刷新后可查看实际结果");
    }

    public static GenerationCostSummary settled(long totalTokens, long creditCost) {
        return new GenerationCostSummary(
                "settled", Math.max(0L, totalTokens), Math.max(0L, creditCost), creditCost > 0,
                null, null, null, null, null, null, null,
                creditCost > 0 ? "已完成积分结算" : "已完成结算，本次未扣除积分");
    }

    private static void requireNonNegative(Long value, String message) {
        if (value != null && value < 0) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
