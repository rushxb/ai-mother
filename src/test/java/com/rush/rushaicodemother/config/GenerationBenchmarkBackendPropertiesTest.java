package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkBackendPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultConfigurationMustRemainValidAndResourceBoundsMustBeEnforced() {
        GenerationBenchmarkBackendProperties properties =
                new GenerationBenchmarkBackendProperties();
        assertTrue(validator.validate(properties).isEmpty());

        properties.setMaxResponseBytes(10 * 1024 * 1024);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setMaxResponseBytes(64 * 1024);
        properties.setProcessTimeout(Duration.ofSeconds(30));
        assertFalse(validator.validate(properties).isEmpty());
    }
}
