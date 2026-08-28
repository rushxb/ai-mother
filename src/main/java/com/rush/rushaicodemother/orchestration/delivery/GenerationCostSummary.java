package com.rush.rushaicodemother.orchestration.delivery;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 任务成本结算摘要；终态提交与账本结算之间显式保留 pending 状态。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationCostSummary(
        String settlementStatus,
        Long totalTokens,
        Long creditCost,
        Boolean charged,
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
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("成本摘要不能为空");
        }
        summary = summary.trim();
    }

    public static GenerationCostSummary pending() {
        return new GenerationCostSummary(
                "pending", null, null, null, "成本正在结算，刷新后可查看实际结果");
    }

    public static GenerationCostSummary settled(long totalTokens, long creditCost) {
        return new GenerationCostSummary(
                "settled", Math.max(0L, totalTokens), Math.max(0L, creditCost), true,
                creditCost > 0 ? "已完成积分结算" : "已完成结算，本次未扣除积分");
    }
}
