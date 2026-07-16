package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.time.Duration;

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
}
