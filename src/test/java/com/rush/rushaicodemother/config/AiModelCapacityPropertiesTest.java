package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiModelCapacityPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustProvideHeartbeatHeadroomAndBoundedCrashRecovery() {
        AiModelCapacityProperties properties = new AiModelCapacityProperties();

        assertTrue(validator.validate(properties).isEmpty());
        assertTrue(properties.getHeartbeatInterval()
                .compareTo(properties.getPermitLease().dividedBy(2)) <= 0);
        assertTrue(properties.getMaximumHold().compareTo(properties.getPermitLease()) > 0);
        assertTrue(properties.getIdleTtl().compareTo(properties.getMaximumHold()) > 0);
    }

    @Test
    void unsafeLeaseRelationshipsAndSubMillisecondDurationsMustBeRejected() {
        AiModelCapacityProperties properties = new AiModelCapacityProperties();
        properties.setHeartbeatInterval(Duration.ofSeconds(31));
        properties.setMaximumHold(Duration.ofSeconds(60));
        properties.setIdleTtl(Duration.ofSeconds(60));
        properties.setAcquireTimeout(Duration.ofNanos(1));

        assertFalse(validator.validate(properties).isEmpty());
    }
}
