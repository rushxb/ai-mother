package com.rush.rushaicodemother.service.credit;

import com.rush.rushaicodemother.config.UserCreditProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserCreditCostCalculatorTest {

    @Test
    void calculateMustRoundUpWithoutOverflow() {
        UserCreditProperties properties = new UserCreditProperties();
        properties.setTokensPerCredit(100_000L);
        UserCreditCostCalculator calculator = new UserCreditCostCalculator(properties);

        assertEquals(0L, calculator.calculate(0L));
        assertEquals(1L, calculator.calculate(1L));
        assertEquals(1L, calculator.calculate(100_000L));
        assertEquals(2L, calculator.calculate(100_001L));
        assertEquals(Long.MAX_VALUE / 100_000L + 1, calculator.calculate(Long.MAX_VALUE));
    }

    @Test
    void calculateMustRejectInvalidRuntimeConfigurationDefensively() {
        UserCreditProperties properties = new UserCreditProperties();
        properties.setTokensPerCredit(0L);
        UserCreditCostCalculator calculator = new UserCreditCostCalculator(properties);

        assertThrows(IllegalStateException.class, () -> calculator.calculate(1L));
    }
}
