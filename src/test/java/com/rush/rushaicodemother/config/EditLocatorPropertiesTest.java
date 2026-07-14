package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditLocatorPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultProductionLimits() {
        assertTrue(validator.validate(new EditLocatorProperties()).isEmpty());
    }

    @Test
    void shouldRejectUnboundedTraversalAndReadLimits() {
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxScannedFiles(1_000_001);
        properties.setMaxReadableFileBytes(104_857_601L);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectTotalContextSmallerThanSingleFileBudget() {
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxSingleFileChars(4_096);
        properties.setMaxTotalContextChars(2_048);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
