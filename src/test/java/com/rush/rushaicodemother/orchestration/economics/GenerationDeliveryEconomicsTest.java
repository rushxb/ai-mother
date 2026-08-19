package com.rush.rushaicodemother.orchestration.economics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationDeliveryEconomicsTest {

    @Test
    void allAttemptCostsMustBeDividedBySuccessfulDeliveries() {
        GenerationDeliveryEconomics economics =
                GenerationDeliveryEconomics.fromTotals(2, 1_000, 10);

        assertEquals(2, economics.successfulDeliveryCount());
        assertEquals(500.0, economics.providerTokensPerSuccessfulDelivery());
        assertEquals(5.0, economics.creditCostPerSuccessfulDelivery());
    }

    @Test
    void noSuccessfulDeliveryMustRemainUndefinedInsteadOfPretendingToBeFree() {
        GenerationDeliveryEconomics economics =
                GenerationDeliveryEconomics.fromTotals(0, 1_000, 10);

        assertEquals(0, economics.successfulDeliveryCount());
        assertNull(economics.providerTokensPerSuccessfulDelivery());
        assertNull(economics.creditCostPerSuccessfulDelivery());
        assertThrows(IllegalArgumentException.class,
                () -> GenerationDeliveryEconomics.fromTotals(-1, 0, 0));
    }
}
