package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.config.GenerationBenchmarkEvidenceProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkEvidenceSignatureServiceTest {

    private static final String SIGNING_SECRET = "release-evidence-test-secret-32-bytes";

    @Test
    void hmacMustVerifyExactPayloadAndRejectMetadataTampering() {
        GenerationBenchmarkEvidenceProperties properties = properties(SIGNING_SECRET);
        GenerationBenchmarkEvidenceSignatureService service =
                new GenerationBenchmarkEvidenceSignatureService(properties);
        GenerationBenchmarkEvidencePayload payload = payload("a".repeat(64), "b".repeat(64));

        String signature = service.sign(payload);

        assertTrue(service.verify(payload, signature));
        assertTrue(service.verify(payload, signature.toUpperCase()));
        assertFalse(service.verify(payload("c".repeat(64), "b".repeat(64)), signature));
        assertFalse(service.verify(payload("a".repeat(64), "d".repeat(64)), signature));
    }

    @Test
    void missingSigningSecretMustFailClosed() {
        GenerationBenchmarkEvidenceSignatureService service =
                new GenerationBenchmarkEvidenceSignatureService(properties("too-short"));

        assertThrows(BusinessException.class,
                () -> service.sign(payload("a".repeat(64), "b".repeat(64))));
    }

    private GenerationBenchmarkEvidenceProperties properties(String secret) {
        GenerationBenchmarkEvidenceProperties properties = new GenerationBenchmarkEvidenceProperties();
        properties.setSigningSecret(secret);
        return properties;
    }

    private GenerationBenchmarkEvidencePayload payload(String candidateFingerprint,
                                                        String reportSha256) {
        Instant evaluatedAt = Instant.parse("2026-07-18T00:00:00Z");
        return new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE,
                "app-generation",
                candidateFingerprint,
                "e".repeat(64),
                "generation-benchmark-graders-v1",
                "f".repeat(64),
                "1234567",
                "1".repeat(64),
                "2".repeat(64),
                reportSha256,
                evaluatedAt,
                evaluatedAt.plusSeconds(3600)
        );
    }
}
