package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedDependencyRegistryTest {

    @Test
    void shouldNormalizeTrustedRegistryAddress() {
        TrustedDependencyRegistry registry = TrustedDependencyRegistry.parse(
                "HTTPS://NPM-REGISTRY.INTERNAL/repository/npm"
        );

        assertEquals(
                "https://npm-registry.internal/repository/npm/",
                registry.url()
        );
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "file:///tmp/npm-registry",
            "http://localhost:4873/",
            "http://127.0.0.1:4873/",
            "http://[::1]:4873/",
            "http://user:secret@npm-registry:4873/",
            "http://npm-registry:4873/?target=https://attacker.invalid",
            "http://npm-registry:4873/a/../b/"
    })
    void shouldRejectAddressThatCannotRepresentTrustedRegistry(String value) {
        assertThrows(
                IllegalArgumentException.class,
                () -> TrustedDependencyRegistry.parse(value)
        );
    }
}
