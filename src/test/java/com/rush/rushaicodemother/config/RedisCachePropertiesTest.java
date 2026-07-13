package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCachePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultCachePolicy() {
        assertTrue(validator.validate(new RedisCacheProperties()).isEmpty());
    }

    @Test
    void shouldRejectNonPositiveOrUnnamedCacheTtl() {
        RedisCacheProperties properties = new RedisCacheProperties();
        properties.setCacheTtl(Map.of(" ", Duration.ZERO));

        assertFalse(validator.validate(properties).isEmpty());
    }
}
