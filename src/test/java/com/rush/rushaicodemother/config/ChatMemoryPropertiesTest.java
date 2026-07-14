package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMemoryPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptProductionDefaults() {
        assertTrue(validator.validate(new ChatMemoryProperties()).isEmpty());
    }

    @Test
    void shouldRejectBlankOrUnsafeKeyPrefix() {
        ChatMemoryProperties blankPrefix = new ChatMemoryProperties();
        blankPrefix.setKeyPrefix(" ");
        ChatMemoryProperties unsafePrefix = new ChatMemoryProperties();
        unsafePrefix.setKeyPrefix("chat memory:*");

        assertFalse(validator.validate(blankPrefix).isEmpty());
        assertFalse(validator.validate(unsafePrefix).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveLimitsAndExpiration() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setTtlSeconds(0);
        properties.setFallbackMaxEntries(0);
        properties.setFallbackExpireAfterAccess(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectMissingFallbackExpiration() {
        ChatMemoryProperties properties = new ChatMemoryProperties();
        properties.setFallbackExpireAfterAccess(null);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
