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
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();

        assertTrue(validator.validate(properties).isEmpty());
        assertTrue(properties.isLocalFirstHeavyRoutingEnabled());
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
    void shouldRejectFirstSignalTimeoutOutsideGenerationWindow() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofSeconds(2));
        assertFalse(validator.validate(properties).isEmpty());

        properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofMinutes(3));
        assertFalse(validator.validate(properties).isEmpty());

        properties = new AiModelRuntimeProperties();
        properties.setGenerationTimeout(Duration.ofSeconds(30));
        properties.setFirstSignalTimeout(Duration.ofSeconds(31));
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

    @Test
    void shouldRejectFirstTokenHedgeDelayOutsideSafeRange() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstTokenHedgeDelay(Duration.ofMillis(249));
        assertFalse(validator.validate(properties).isEmpty());

        properties.setFirstTokenHedgeDelay(Duration.ofSeconds(31));
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidRootModelRetryPolicy() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setRootModelRetryMinDelay(Duration.ofMillis(99));
        assertFalse(validator.validate(properties).isEmpty());

        properties = new AiModelRuntimeProperties();
        properties.setRootModelRetryMinDelay(Duration.ofSeconds(10));
        properties.setRootModelRetryMaxDelay(Duration.ofSeconds(9));
        assertFalse(validator.validate(properties).isEmpty());

        properties = new AiModelRuntimeProperties();
        properties.setRootModelRetryJitter(1.01);
        assertFalse(validator.validate(properties).isEmpty());
    }
}
