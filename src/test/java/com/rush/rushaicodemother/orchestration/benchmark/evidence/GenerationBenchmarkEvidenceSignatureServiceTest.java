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
        GenerationBenchmarkEvidencePayload payload = payload(
                "a".repeat(64), "b".repeat(64), 1L);

        String signature = service.sign(payload);

        assertTrue(service.verify(payload, signature));
        assertTrue(service.verify(payload, signature.toUpperCase()));
        assertFalse(service.verify(
                payload("c".repeat(64), "b".repeat(64), 1L), signature));
        assertFalse(service.verify(
                payload("a".repeat(64), "d".repeat(64), 1L), signature));
        assertFalse(service.verify(
                payload("a".repeat(64), "b".repeat(64), 2L), signature));
    }

    @Test
    void missingSigningSecretMustFailClosed() {
        GenerationBenchmarkEvidenceSignatureService service =
                new GenerationBenchmarkEvidenceSignatureService(properties("too-short"));

        assertThrows(BusinessException.class,
                () -> service.sign(payload("a".repeat(64), "b".repeat(64), 1L)));
    }

    @Test
    void signerMustRefuseLegacyProtocolForNewEvidence() {
        GenerationBenchmarkEvidenceProperties properties = properties(SIGNING_SECRET);
        GenerationBenchmarkEvidenceSignatureService service =
                new GenerationBenchmarkEvidenceSignatureService(properties);
        GenerationBenchmarkEvidencePayload current = payload(
                "a".repeat(64), "b".repeat(64), 1L);
        GenerationBenchmarkEvidencePayload legacy = new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.LEGACY_SIGNATURE_VERSION,
                current.subjectType(),
                current.subjectKey(),
                current.candidateFingerprint(),
                0L,
                current.datasetFingerprint(),
                current.graderFingerprint(),
                current.runtimeConfigFingerprint(),
                current.gitCommit(),
                current.modelFingerprint(),
                current.promptBundleFingerprint(),
                current.reportSha256(),
                current.evaluatedAt(),
                current.expiresAt()
        );

        assertThrows(IllegalArgumentException.class, () -> service.sign(legacy));
    }

    private GenerationBenchmarkEvidenceProperties properties(String secret) {
        GenerationBenchmarkEvidenceProperties properties = new GenerationBenchmarkEvidenceProperties();
        properties.setSigningSecret(secret);
        return properties;
    }

    private GenerationBenchmarkEvidencePayload payload(String candidateFingerprint,
                                                        String reportSha256,
                                                        long candidatePhysicalRequestCount) {
        Instant evaluatedAt = Instant.parse("2026-07-18T00:00:00Z");
        return new GenerationBenchmarkEvidencePayload(
                GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION,
                GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                "7",
                candidateFingerprint,
                candidatePhysicalRequestCount,
                "e".repeat(64),
                "generation-benchmark-graders-v1",
                "f".repeat(64),
                "1".repeat(40),
                "1".repeat(64),
                "2".repeat(64),
                reportSha256,
                evaluatedAt,
                evaluatedAt.plusSeconds(3600)
        );
    }
}
