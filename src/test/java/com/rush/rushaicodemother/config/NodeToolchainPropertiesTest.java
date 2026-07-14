package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeToolchainPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultExecutables() {
        assertTrue(validator.validate(new NodeToolchainProperties()).isEmpty());
    }

    @Test
    void shouldRejectBlankExecutables() {
        NodeToolchainProperties properties = new NodeToolchainProperties();
        properties.setNodeExecutable(" ");
        properties.setPnpmExecutable("");

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectNullCharacterInExecutables() {
        NodeToolchainProperties properties = new NodeToolchainProperties();
        properties.setPnpmExecutable("pnpm\0evil");

        assertFalse(validator.validate(properties).isEmpty());
    }
}
