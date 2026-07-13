package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyInstallPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultProductionLimits() {
        assertTrue(validator.validate(new DependencyInstallProperties()).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveDurations() {
        DependencyInstallProperties properties = new DependencyInstallProperties();
        properties.setIdleTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectHeartbeatNotSmallerThanCommandTimeout() {
        DependencyInstallProperties properties = new DependencyInstallProperties();
        properties.setCommandTimeout(Duration.ofSeconds(5));
        properties.setHeartbeatInterval(Duration.ofSeconds(5));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedRetryAndOutputSettings() {
        DependencyInstallProperties properties = new DependencyInstallProperties();
        properties.setMaxAttempts(6);
        properties.setMaxOutputLength(1_000_001);
        properties.setLockStripes(0);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
