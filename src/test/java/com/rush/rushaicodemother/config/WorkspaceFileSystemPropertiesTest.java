package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceFileSystemPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptDefaultLimits() {
        assertTrue(validator.validate(new WorkspaceFileSystemProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidInteractiveLimits() {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setMaxInteractiveFileBytes(1_023);
        properties.setMaxInteractiveTreeDepth(0);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectCopyTotalBelowSingleFileLimit() {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setMaxFileBytes(2_000_000);
        properties.setMaxCopyBytes(1_000_000);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidPublishRetryLimits() {
        WorkspaceFileSystemProperties properties = new WorkspaceFileSystemProperties();
        properties.setPublishMaxAttempts(0);
        properties.setPublishRetryDelayMillis(5_001);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
