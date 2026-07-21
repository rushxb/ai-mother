package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerWebSocketProxyPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptBoundedDefaults() {
        assertTrue(validator.validate(new DevServerWebSocketProxyProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidTimeoutsAndBuffers() {
        DevServerWebSocketProxyProperties properties = new DevServerWebSocketProxyProperties();
        properties.setConnectTimeout(Duration.ZERO);
        properties.setSendBufferSize(DataSize.ofBytes(0));
        properties.setMaxMessageSize(DataSize.ofBytes((long) Integer.MAX_VALUE + 1));

        assertFalse(validator.validate(properties).isEmpty());
    }
}
