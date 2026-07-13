package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectCommandPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultProductionLimits() {
        assertTrue(validator.validate(new ProjectCommandProperties()).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveDuration() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setIdleTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectHeartbeatNotSmallerThanCommandTimeout() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setHeartbeatInterval(properties.getLightValidationTimeout());

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedOutput() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setMaxOutputLength(1_000_001);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
