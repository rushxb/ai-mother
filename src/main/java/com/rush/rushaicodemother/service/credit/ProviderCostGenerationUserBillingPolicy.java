package com.rush.rushaicodemother.service.credit;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 默认的 Provider 成本计费策略。
 *
 * <p>成功调用和用户主动取消的物理调用计费，关闭“读取部分流后反复取消”的免费
 * 消耗窗口；Provider 错误与系统超时保留成本事实但默认向用户减免。</p>
 */
@Component
public final class ProviderCostGenerationUserBillingPolicy
        implements GenerationUserBillingPolicy {

    public static final String POLICY_REFERENCE = "provider-cost-v1";

    @Override
    public UserBillingDecision decide(ProviderCostObservation observation) {
        Objects.requireNonNull(observation, "provider cost observation cannot be null");
        if (observation.hasPendingAttempts()) {
            throw new IllegalStateException(
                    "provider cost observation has unsettled physical attempts");
        }
        long chargeableTokens = Math.addExact(
                observation.successfulTokens(), observation.cancelledTokens());
        long waivedTokens = Math.addExact(
                observation.timedOutTokens(), observation.failedTokens());
        return new UserBillingDecision(
                chargeableTokens,
                observation.totalObservedTokens(),
                waivedTokens,
                POLICY_REFERENCE
        );
    }
}
