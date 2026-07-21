package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevServerValidationResultTest {

    @Test
    void timeoutDiagnosticMustExposeStructuredStartupClassification() {
        DevServerValidationResult result = DevServerValidationResult.timeout("task-timeout", 91L, 30_000);

        String diagnostic = result.toPublicRepairDiagnostic();

        assertTrue(diagnostic.contains("validationStage=runtime"));
        assertTrue(diagnostic.contains("status=TIMEOUT"));
        assertTrue(diagnostic.contains("failureKind=STARTUP_TIMEOUT"));
    }

    @Test
    void runtimeDiagnosticMustPreserveUsefulEvidenceAndRedactSecrets() {
        DevServerError error = DevServerError.tryMatch(
                "[vite] Pre-transform error: Failed to resolve import \"secret-token=raw-value\" from \"C:\\Users\\rush\\src\\main.ts\"");
        DevServerValidationResult result = DevServerValidationResult.failed(
                "task-runtime", 92L, List.of(error), 50);

        String diagnostic = result.toPublicRepairDiagnostic();

        assertTrue(diagnostic.contains("failureKind=RUNTIME_ERROR"));
        assertTrue(diagnostic.contains("MISSING_IMPORT"));
        assertTrue(diagnostic.contains("[REDACTED]"));
        assertTrue(diagnostic.contains("[path]/main.ts"));
        assertFalse(diagnostic.contains("raw-value"));
        assertFalse(diagnostic.contains("C:\\Users\\rush"));
    }
}
