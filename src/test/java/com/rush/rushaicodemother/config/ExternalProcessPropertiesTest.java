package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalProcessPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultGracePeriod() {
        assertTrue(validator.validate(new ExternalProcessProperties()).isEmpty());
    }

    @Test
    void shouldRejectExcessiveGracePeriod() {
        ExternalProcessProperties properties = new ExternalProcessProperties();
        properties.setTerminationGracePeriod(Duration.ofMinutes(6));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveGracePeriod() {
        ExternalProcessProperties properties = new ExternalProcessProperties();
        properties.setTerminationGracePeriod(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
