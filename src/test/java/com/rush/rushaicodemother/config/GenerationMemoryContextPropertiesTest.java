package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationMemoryContextPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustKeepBothConcurrencyPoliciesDisabled() {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();

        assertFalse(properties.isParallelReadsEnabled());
        assertFalse(properties.isPreparationOverlapEnabled());
        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void preparationOverlapTimeoutMustBePositive() {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setPreparationOverlapTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void preparationOverlapConcurrencyMustStayBounded() {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setMaxConcurrentPreparationOverlaps(65);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
