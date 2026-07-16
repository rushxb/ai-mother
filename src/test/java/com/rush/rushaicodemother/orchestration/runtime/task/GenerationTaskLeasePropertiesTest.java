package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskLeasePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustProvideSafeHeartbeatHeadroom() {
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();

        assertTrue(validator.validate(properties).isEmpty());
        assertTrue(properties.getHeartbeatInterval().compareTo(properties.getLeaseDuration()) < 0);
    }

    @Test
    void heartbeatMustBeShorterThanLeaseAndRecoveryBatchMustBeBounded() {
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(30));
        properties.setLeaseDuration(Duration.ofSeconds(30));
        properties.setRecoveryBatchSize(501);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
