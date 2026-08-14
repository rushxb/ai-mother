package com.rush.rushaicodemother.service.credit;

/**
 * 一个生成任务的 Provider 成本事实聚合。
 *
 * <p>底层账本仍按物理 attempt 保存；该不可变快照只在用户结算时聚合，避免把
 * Provider 成本事实和产品收费策略写进同一条 SQL。</p>
 */
public record ProviderCostObservation(
        long successfulTokens,
        long cancelledTokens,
        long timedOutTokens,
        long failedTokens,
        long pendingAttemptCount
) {

    public ProviderCostObservation {
        if (successfulTokens < 0 || cancelledTokens < 0 || timedOutTokens < 0
                || failedTokens < 0 || pendingAttemptCount < 0) {
            throw new IllegalArgumentException("provider cost observation cannot be negative");
        }
    }

    public static ProviderCostObservation none() {
        return new ProviderCostObservation(0L, 0L, 0L, 0L, 0L);
    }

    public long totalObservedTokens() {
        return Math.addExact(
                Math.addExact(successfulTokens, cancelledTokens),
                Math.addExact(timedOutTokens, failedTokens)
        );
    }

    public boolean hasPendingAttempts() {
        return pendingAttemptCount > 0;
    }
}
