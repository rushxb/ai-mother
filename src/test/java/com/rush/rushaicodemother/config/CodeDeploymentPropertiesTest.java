package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeDeploymentPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldNotProvideImplicitDeploymentHost() {
        assertFalse(validator.validate(new CodeDeploymentProperties()).isEmpty());
    }

    @Test
    void shouldAcceptHttpAndHttpsAbsoluteAddresses() {
        assertTrue(isValid("http://localhost:91"));
        assertTrue(isValid("https://deploy.example.com/static/"));
    }

    @Test
    void shouldRejectBlankOrRelativeAddresses() {
        assertFalse(isValid(null));
        assertFalse(isValid("   "));
        assertFalse(isValid("/deploy"));
    }

    @Test
    void shouldRejectUnsupportedSchemesAndInvalidPorts() {
        assertFalse(isValid("ftp://deploy.example.com"));
        assertFalse(isValid("https://deploy.example.com:0"));
        assertFalse(isValid("https://deploy.example.com:65536"));
    }

    @Test
    void shouldRejectCredentialsQueryAndFragment() {
        assertFalse(isValid("https://user:password@deploy.example.com"));
        assertFalse(isValid("https://deploy.example.com?tenant=1"));
        assertFalse(isValid("https://deploy.example.com/#preview"));
    }

    private boolean isValid(String deployHost) {
        CodeDeploymentProperties properties = new CodeDeploymentProperties();
        properties.setDeployHost(deployHost);
        return validator.validate(properties).isEmpty();
    }
}
