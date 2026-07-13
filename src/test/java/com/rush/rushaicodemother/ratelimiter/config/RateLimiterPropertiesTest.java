package com.rush.rushaicodemother.ratelimiter.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptProductionDefaults() {
        assertTrue(validator.validate(new RateLimiterProperties()).isEmpty());
    }

    @Test
    void shouldRejectInvalidPoolDurationsAndProxyEntries() {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setConnectionMinimumIdleSize(5);
        properties.setConnectionPoolSize(4);
        properties.setConnectTimeout(Duration.ZERO);
        properties.setTrustedProxies(List.of(" "));

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldBindCommaSeparatedTrustedProxyEnvironmentValue() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.rate-limiter.trusted-proxies",
                "10.0.0.0/8,192.168.0.0/16"
        ));

        RateLimiterProperties properties = new Binder(source)
                .bind("app.rate-limiter", Bindable.of(RateLimiterProperties.class))
                .orElseThrow(() -> new AssertionError("限流配置绑定失败"));

        assertEquals(List.of("10.0.0.0/8", "192.168.0.0/16"), properties.getTrustedProxies());
    }
}
