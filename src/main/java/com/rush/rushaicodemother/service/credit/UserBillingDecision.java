package com.rush.rushaicodemother.service.credit;

/** Provider 成本事实经过产品策略后得到的用户结算决定。 */
public record UserBillingDecision(
        long chargeableTokens,
        long providerObservedTokens,
        long waivedTokens,
        String policyReference
) {

    public UserBillingDecision {
        if (chargeableTokens < 0 || providerObservedTokens < 0 || waivedTokens < 0
                || policyReference == null || policyReference.isBlank()) {
            throw new IllegalArgumentException("user billing decision is invalid");
        }
        if (Math.addExact(chargeableTokens, waivedTokens) != providerObservedTokens) {
            throw new IllegalArgumentException(
                    "charged and waived tokens must equal observed provider cost");
        }
    }
}
