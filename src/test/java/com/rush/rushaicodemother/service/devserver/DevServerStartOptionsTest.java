package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevServerStartOptionsTest {

    @Test
    void managedOptionsMustRequireTaskIdentityAndPositiveTimeout() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new DevServerStartOptions(" ", Duration.ofSeconds(1), () -> false)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DevServerStartOptions("task-1", Duration.ZERO, () -> false)
        );
    }

    @Test
    void nullCancellationSignalMustDefaultToNotCancelled() {
        DevServerStartOptions options = new DevServerStartOptions(
                "task-1",
                Duration.ofSeconds(1),
                null
        );

        assertFalse(options.isCancellationRequested());
    }

    @Test
    void environmentOverridesMustOnlyAllowControlledLoopbackApiBase() {
        DevServerStartOptions options = new DevServerStartOptions(
                "task-1",
                Duration.ofSeconds(1),
                null,
                null,
                Map.of("VITE_API_BASE_URL", "http://127.0.0.1:19001/api")
        );

        assertEquals(
                Map.of("VITE_API_BASE_URL", "http://127.0.0.1:19001/api"),
                options.environmentOverrides()
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DevServerStartOptions(
                        "task-1",
                        Duration.ofSeconds(1),
                        null,
                        null,
                        Map.of("NODE_OPTIONS", "--require untrusted.js")
                )
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new DevServerStartOptions(
                        "task-1",
                        Duration.ofSeconds(1),
                        null,
                        null,
                        Map.of("VITE_API_BASE_URL", "https://example.com/api")
                )
        );
    }
}
