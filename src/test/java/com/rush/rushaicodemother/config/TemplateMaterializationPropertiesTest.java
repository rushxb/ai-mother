package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateMaterializationPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptProductionDefaults() {
        assertTrue(validator.validate(new TemplateMaterializationProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidCountPathAndDepthLimits() {
        TemplateMaterializationProperties properties = new TemplateMaterializationProperties();
        properties.setMaxFiles(0);
        properties.setMaxRelativePathLength(0);
        properties.setMaxDirectoryDepth(0);
        properties.setPublishMaxAttempts(0);
        properties.setPublishRetryDelayMillis(-1);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectTotalByteLimitBelowSingleFileLimit() {
        TemplateMaterializationProperties properties = new TemplateMaterializationProperties();
        properties.setMaxFileBytes(10L * 1024 * 1024);
        properties.setMaxTotalBytes(1024L);

        assertFalse(validator.validate(properties).isEmpty());
    }
}