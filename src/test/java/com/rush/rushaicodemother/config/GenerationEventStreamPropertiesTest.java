package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationEventStreamPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptProductionDefaultsAndFlushIntervalBoundaries() {
        GenerationEventStreamProperties defaults = new GenerationEventStreamProperties();
        GenerationEventStreamProperties lowerBoundary = new GenerationEventStreamProperties();
        lowerBoundary.setDeltaFlushInterval(Duration.ofMillis(10));
        GenerationEventStreamProperties upperBoundary = new GenerationEventStreamProperties();
        upperBoundary.setDeltaFlushInterval(Duration.ofSeconds(1));

        assertTrue(validator.validate(defaults).isEmpty());
        assertTrue(validator.validate(lowerBoundary).isEmpty());
        assertTrue(validator.validate(upperBoundary).isEmpty());
    }

    @Test
    void shouldRejectFlushIntervalsOutsideTheOperationalRange() {
        GenerationEventStreamProperties tooShort = new GenerationEventStreamProperties();
        tooShort.setDeltaFlushInterval(Duration.ofMillis(9));
        GenerationEventStreamProperties tooLong = new GenerationEventStreamProperties();
        tooLong.setDeltaFlushInterval(Duration.ofMillis(1_001));
        GenerationEventStreamProperties missing = new GenerationEventStreamProperties();
        missing.setDeltaFlushInterval(null);

        assertFalse(validator.validate(tooShort).isEmpty());
        assertFalse(validator.validate(tooLong).isEmpty());
        assertFalse(validator.validate(missing).isEmpty());
    }

    @Test
    void shouldRejectDeltaCharacterLimitsOutsideTheBoundedRange() {
        GenerationEventStreamProperties tooSmall = new GenerationEventStreamProperties();
        tooSmall.setDeltaMaxChars(63);
        GenerationEventStreamProperties tooLarge = new GenerationEventStreamProperties();
        tooLarge.setDeltaMaxChars(65_537);

        assertFalse(validator.validate(tooSmall).isEmpty());
        assertFalse(validator.validate(tooLarge).isEmpty());
    }
}
