package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoToolchainPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultExecutableAndRejectControlCharacters() {
        GoToolchainProperties properties = new GoToolchainProperties();
        assertTrue(validator.validate(properties).isEmpty());

        properties.setGoExecutable("go\0.exe");
        assertFalse(validator.validate(properties).isEmpty());
    }
}
