package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkBrowserPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void settleDelayMustRemainBounded() {
        GenerationBenchmarkBrowserProperties properties =
                new GenerationBenchmarkBrowserProperties();
        assertTrue(validator.validate(properties).isEmpty());

        properties.setSettleDelay(Duration.ofSeconds(31));
        assertFalse(validator.validate(properties).isEmpty());

        properties.setSettleDelay(Duration.ofMillis(-1));
        assertFalse(validator.validate(properties).isEmpty());
    }
}
