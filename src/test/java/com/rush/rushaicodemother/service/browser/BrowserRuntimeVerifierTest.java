package com.rush.rushaicodemother.service.browser;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BrowserRuntimeVerifierTest {

    @Test
    void failedApiRequestMustBlockRuntimeValidation() {
        URI target = URI.create("http://127.0.0.1:5180/");
        BrowserRuntimeProbe probe = mock(BrowserRuntimeProbe.class);
        when(probe.inspect(target, Duration.ZERO)).thenReturn(new BrowserRuntimeObservation(
                target,
                target,
                "Dashboard",
                "complete",
                120,
                1,
                true,
                1,
                12,
                1_600,
                900,
                false,
                "Dashboard content",
                "Dashboard",
                List.of("http://127.0.0.1:5180/src/main.ts"),
                List.of(),
                List.of(),
                BrowserRuntimeObservation.NetworkEvidence.captured(List.of(
                        new BrowserRuntimeObservation.NetworkFailure(
                                "http://127.0.0.1:19101/api/projects",
                                500,
                                "Internal Server Error"
                        )
                )),
                new BrowserRuntimeObservation.ScreenshotStats(true, 1_600, 900, 12, 180)
        ));
        BrowserRuntimeVerifier verifier = new BrowserRuntimeVerifier(probe);

        BrowserRuntimeValidationResult result = verifier.verify(
                target,
                new BrowserRuntimeValidationPolicy(Duration.ZERO, false)
        );

        assertFalse(result.runtimePassed());
        assertTrue(result.runtimeViolations().contains("browser_network_error"));
        assertTrue(result.toPublicRepairDiagnostic().contains("status=500"));
    }

    @Test
    void benchmarkProbeFailureMustFailRuntimeAndVisualEvidence() {
        URI target = URI.create("http://127.0.0.1:5180/");
        BrowserRuntimeProbe probe = mock(BrowserRuntimeProbe.class);
        when(probe.inspect(target, Duration.ZERO))
                .thenThrow(new IllegalStateException("chrome unavailable"));
        BrowserRuntimeVerifier verifier = new BrowserRuntimeVerifier(probe);

        BrowserRuntimeValidationResult result = verifier.verify(
                target,
                new BrowserRuntimeValidationPolicy(Duration.ZERO, true)
        );

        assertFalse(result.runtimePassed());
        assertFalse(result.visualPassed());
        assertFalse(result.passed());
        assertTrue(result.visualViolations()
                .contains("browser_visual_evidence_missing"));
    }
}
