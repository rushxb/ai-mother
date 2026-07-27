package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolWorkspacePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsShouldBeValid() {
        assertTrue(validator.validate(new AiToolWorkspaceProperties()).isEmpty());
    }

    @Test
    void shouldRejectUnboundedReadAndTraversalLimits() {
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxReadableFileBytes(10_485_761L);
        properties.setMaxDirectoryEntries(100_001);
        properties.setMaxDirectoryDepth(129);
        properties.setMaxBatchWriteFiles(101);
        properties.setMaxBatchWriteTotalChars(5_000_001);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveTraversalLimits() {
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxDirectoryEntries(0);
        properties.setMaxDirectoryDepth(0);
        properties.setMaxBatchWriteFiles(0);
        properties.setMaxBatchWriteTotalChars(1_023);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
