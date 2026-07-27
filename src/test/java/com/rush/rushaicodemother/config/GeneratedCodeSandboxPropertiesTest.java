package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedCodeSandboxPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptSecureDefaults() {
        assertTrue(validator.validate(new GeneratedCodeSandboxProperties()).isEmpty());
    }

    @Test
    void shouldRejectMissingModeAndUnsafeContainerMount() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.setMode(null);
        properties.getContainer().setWorkspaceMount("/");

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectHostAndContainerNamespaceNetworks() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setDependencyNetwork("host");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setDependencyNetwork("container:privileged-service");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setDependencyNetwork("bridge");
        properties.getContainer().setDevServerNetwork("host");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setDevServerNetwork("ai-code-sandbox-internal");
        properties.getContainer().setPreviewGatewayNetwork("container:gateway");
        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectInvalidResourceAndIdentityValues() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setMemory("unlimited");
        properties.getContainer().setTmpfsSize("0");
        properties.getContainer().setGoBuildTmpfsSize("unlimited");
        properties.getContainer().setUser("root");
        properties.getContainer().setCleanupTimeout(Duration.ZERO);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectUnsafeOrOverlappingPnpmStoreConfiguration() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setPnpmStoreVolume("../host-cache");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setPnpmStoreVolume("ai-code-mother-pnpm-store-v9");
        properties.getContainer().setPnpmStoreMount("/workspace/.pnpm-store");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setPnpmStoreMount("/tmp/pnpm-store");
        assertFalse(validator.validate(properties).isEmpty());

        properties.getContainer().setPnpmStoreMount("/pnpm/store");
        properties.getContainer().setDependencyCacheEnabled(true);
        assertTrue(validator.validate(properties).isEmpty());
    }
}
