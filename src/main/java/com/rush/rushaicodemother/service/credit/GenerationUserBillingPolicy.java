package com.rush.rushaicodemother.service.credit;

/** Provider 成本事实到用户收费决定的可替换策略 seam。 */
public interface GenerationUserBillingPolicy {

    UserBillingDecision decide(ProviderCostObservation observation);
}
