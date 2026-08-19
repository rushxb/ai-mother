package com.rush.rushaicodemother.orchestration.verification.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedBackendRuntimeRequestTest {

    @Test
    void taskWindowMustClampConfiguredRuntimeLimit() {
        GeneratedBackendRuntimeRequest request = new GeneratedBackendRuntimeRequest(
                Path.of("backend"),
                Duration.ofMillis(250),
                () -> false
        );

        assertEquals(Duration.ofMillis(250), request.clamp(Duration.ofSeconds(45)));
        assertEquals(Duration.ofMillis(100), request.clamp(Duration.ofMillis(100)));
    }

    @Test
    void unmanagedRequestMustPreserveConfiguredRuntimeLimit() {
        GeneratedBackendRuntimeRequest request = GeneratedBackendRuntimeRequest.unmanaged(
                Path.of("backend")
        );

        assertEquals(Duration.ofSeconds(45), request.clamp(Duration.ofSeconds(45)));
        assertFalse(request.isCancellationRequested());
    }

    @Test
    void cancellationProbeMustRemainLiveForTheWholeRuntimeWindow() {
        AtomicBoolean cancellationRequested = new AtomicBoolean();
        GeneratedBackendRuntimeRequest request = new GeneratedBackendRuntimeRequest(
                Path.of("backend"),
                Duration.ofSeconds(1),
                cancellationRequested::get
        );

        assertFalse(request.isCancellationRequested());
        cancellationRequested.set(true);
        assertTrue(request.isCancellationRequested());
    }
}
