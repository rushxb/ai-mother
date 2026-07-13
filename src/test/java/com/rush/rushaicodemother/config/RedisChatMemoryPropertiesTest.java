package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisChatMemoryPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptLocalRedisWithoutPassword() {
        RedisChatMemoryProperties properties = new RedisChatMemoryProperties();
        properties.setHost("localhost");
        properties.setPassword("");

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectBlankHostInvalidPortAndNonPositiveTtl() {
        RedisChatMemoryProperties properties = new RedisChatMemoryProperties();
        properties.setHost(" ");
        properties.setPort(0);
        properties.setTtl(0);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
