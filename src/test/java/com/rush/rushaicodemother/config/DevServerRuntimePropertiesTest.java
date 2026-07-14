package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerRuntimePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultRuntimeLimits() {
        assertTrue(validator.validate(new DevServerRuntimeProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidDurationsAndPollingRelationship() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setValidationErrorCollectionWindow(Duration.ZERO);
        properties.setStartupTimeout(Duration.ofMillis(100));
        properties.setReadinessPollInterval(Duration.ofMillis(100));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectExcessiveRuntimeDuration() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setStartupTimeout(Duration.ofHours(2));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidPortRange() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setPortRangeStart(20000);
        properties.setPortRangeEnd(10000);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedResourceLimits() {
        DevServerRuntimeProperties properties = new DevServerRuntimeProperties();
        properties.setMaxServersPerUser(101);
        properties.setMaxOutputLineLength(100_001);
        properties.setMaxRecentOutputLines(10_001);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
