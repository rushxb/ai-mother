package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelRuntimePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultRuntimeConfiguration() {
        assertTrue(validator.validate(new AiModelRuntimeProperties()).isEmpty());
    }

    @Test
    void shouldRejectRoutingTimeoutOutsideSafeRange() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setRoutingTimeout(Duration.ofSeconds(2));
        assertFalse(validator.validate(properties).isEmpty());

        properties.setRoutingTimeout(Duration.ofMinutes(6));
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectGenerationTimeoutOutsideSafeRange() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setGenerationTimeout(Duration.ofSeconds(2));
        assertFalse(validator.validate(properties).isEmpty());

        properties.setGenerationTimeout(Duration.ofMinutes(16));
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectCreateSpecTimeoutOutsideShortRequestRange() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setCreateSpecTimeout(Duration.ofSeconds(11));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectRetryCountOutsideConfiguredRange() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setRoutingMaxRetries(6);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnboundedFailoverCandidateCount() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFailoverMaxCandidates(0);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setFailoverMaxCandidates(6);
        assertFalse(validator.validate(properties).isEmpty());
    }
}
