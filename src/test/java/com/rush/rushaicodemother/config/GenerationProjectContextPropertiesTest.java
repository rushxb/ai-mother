package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationProjectContextPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultProductionLimits() {
        assertTrue(validator.validate(new GenerationProjectContextProperties()).isEmpty());
    }

    @Test
    void shouldRejectTotalBudgetSmallerThanSingleFileBudget() {
        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxSingleFileChars(4_096);
        properties.setMaxTotalContextChars(2_048);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedReadableFileLimit() {
        GenerationProjectContextProperties properties = new GenerationProjectContextProperties();
        properties.setMaxReadableFileBytes(104_857_601L);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
