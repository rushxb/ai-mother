package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CosClientPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAllowEmptyConfigurationWhenCosIsDisabled() {
        assertTrue(validator.validate(new CosClientProperties()).isEmpty());
    }

    @Test
    void shouldAcceptCompleteEnabledConfigurationIncludingLocalhost() {
        CosClientProperties properties = enabledProperties();
        properties.setHost("http://localhost:9000");

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectIncompleteEnabledConfiguration() {
        CosClientProperties properties = enabledProperties();
        properties.setSecretKey(" ");

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectHostWithPathOrUnsupportedScheme() {
        CosClientProperties properties = enabledProperties();
        properties.setHost("https://cos.example.com/files");
        assertFalse(validator.validate(properties).isEmpty());

        properties.setHost("ftp://cos.example.com");
        assertFalse(validator.validate(properties).isEmpty());
    }

    private CosClientProperties enabledProperties() {
        CosClientProperties properties = new CosClientProperties();
        properties.setEnabled(true);
        properties.setHost("https://cos.example.com");
        properties.setSecretId("secret-id");
        properties.setSecretKey("secret-key");
        properties.setRegion("ap-beijing");
        properties.setBucket("bucket-123");
        return properties;
    }
}
