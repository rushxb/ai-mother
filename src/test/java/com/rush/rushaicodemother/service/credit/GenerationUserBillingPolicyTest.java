package com.rush.rushaicodemother.service.credit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationUserBillingPolicyTest {

    private final GenerationUserBillingPolicy policy =
            new ProviderCostGenerationUserBillingPolicy();

    @Test
    void successAndUserCancellationMustBeBilledWhileProviderFailuresRemainObservable() {
        ProviderCostObservation observation = new ProviderCostObservation(
                100L,
                30L,
                40L,
                20L,
                0L
        );

        UserBillingDecision decision = policy.decide(observation);

        assertEquals(130L, decision.chargeableTokens());
        assertEquals(190L, decision.providerObservedTokens());
        assertEquals(60L, decision.waivedTokens());
        assertEquals("provider-cost-v1", decision.policyReference());
    }

    @Test
    void pendingPhysicalAttemptMustNotProduceAZeroCostBillingDecision() {
        ProviderCostObservation observation =
                new ProviderCostObservation(0L, 0L, 0L, 0L, 1L);

        assertThrows(IllegalStateException.class, () -> policy.decide(observation));
    }
}
