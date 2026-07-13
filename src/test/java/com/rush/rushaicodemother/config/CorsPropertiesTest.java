package com.rush.rushaicodemother.config;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CorsPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void shouldAcceptExplicitHttpOrigins() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://console.example.com", "http://localhost:5173"));

        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void shouldRejectWildcardOrOriginContainingPath() {
        CorsProperties wildcardProperties = new CorsProperties();
        wildcardProperties.setAllowedOrigins(List.of("https://*.example.com"));

        CorsProperties pathProperties = new CorsProperties();
        pathProperties.setAllowedOrigins(List.of("https://console.example.com/app"));

        assertFalse(validator.validate(wildcardProperties).isEmpty());
        assertFalse(validator.validate(pathProperties).isEmpty());
    }

    @Test
    void shouldBindCommaSeparatedOriginsFromEnvironmentStyleProperty() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "app.cors.allowed-origins",
                "https://console.example.com,https://admin.example.com"
        ));

        CorsProperties properties = new Binder(source)
                .bind("app.cors", Bindable.of(CorsProperties.class))
                .orElseThrow(() -> new AssertionError("CORS properties were not bound"));

        assertTrue(properties.getAllowedOrigins().equals(List.of(
                "https://console.example.com",
                "https://admin.example.com"
        )));
    }

    @Test
    void shouldRejectUnsupportedHttpMethod() {
        CorsProperties properties = new CorsProperties();
        properties.setAllowedOrigins(List.of("https://console.example.com"));
        properties.setAllowedMethods(List.of("GET", "TRACE"));

        assertFalse(validator.validate(properties).isEmpty());
    }
}