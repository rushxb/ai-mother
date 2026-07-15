package com.rush.rushaicodemother.service.artifact;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentKeyPolicyTest {

    private final DeploymentKeyPolicy policy = new DeploymentKeyPolicy();

    @Test
    void shouldAcceptOnlyBoundedAlphaNumericDeploymentKeys() {
        assertTrue(policy.isValid("Deploy123"));
        assertTrue(policy.isValid("A1B2C3"));
        assertFalse(policy.isValid(null));
        assertFalse(policy.isValid("short"));
        assertFalse(policy.isValid("Deploy-123"));
        assertFalse(policy.isValid("Deploy_123"));
        assertFalse(policy.isValid("A".repeat(65)));
    }

    @Test
    void shouldRejectInvalidKeyThroughRequiredValidation() {
        assertThrows(IllegalArgumentException.class, () -> policy.requireValid("../Deploy123"));
    }
}
