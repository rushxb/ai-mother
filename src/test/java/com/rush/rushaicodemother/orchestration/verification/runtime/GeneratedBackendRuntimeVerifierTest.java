package com.rush.rushaicodemother.orchestration.verification.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GeneratedBackendRuntimeVerifierTest {

    @TempDir
    Path backendProject;

    @Test
    void healthyBackendMustReturnObservedRuntimeEvidenceAndReleaseHandle() {
        GeneratedBackendRuntime runtime = mock(GeneratedBackendRuntime.class);
        AtomicBoolean closed = new AtomicBoolean(false);
        when(runtime.start(backendProject)).thenReturn(new GeneratedBackendRuntimeHandle(
                19_201,
                GeneratedBackendRuntimeObservation.passed(),
                () -> true,
                () -> closed.set(true)));
        GeneratedBackendRuntimeVerifier verifier = new GeneratedBackendRuntimeVerifier(runtime);

        BackendRuntimeValidationResult result = verifier.verify(backendProject);

        assertTrue(result.passed());
        assertTrue(result.processAlive());
        assertEquals(19_201, result.port());
        assertEquals("go run -mod=readonly ./cmd/server", result.commandSummary());
        assertTrue(result.durationMs() >= 0);
        assertTrue(closed.get());
    }
}
