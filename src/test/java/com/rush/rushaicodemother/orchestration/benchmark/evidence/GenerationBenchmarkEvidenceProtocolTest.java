package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkEvidenceProtocolTest {

    @Test
    void currentProtocolMustBindPhysicalRequestsToCandidateType() {
        int current = GenerationBenchmarkEvidenceProtocol.CURRENT_SIGNATURE_VERSION;

        assertTrue(GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                current, GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, 1L));
        assertFalse(GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                current, GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, 0L));
        assertTrue(GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                current, GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, 0L));
        assertFalse(GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                current, GenerationBenchmarkEvidenceSubject.PROMPT_RELEASE, 1L));
    }

    @Test
    void legacyProtocolMustRemainRecognizableButNotCurrent() {
        int legacy = GenerationBenchmarkEvidenceProtocol.LEGACY_SIGNATURE_VERSION;

        assertTrue(GenerationBenchmarkEvidenceProtocol.hasValidAttestation(
                legacy, GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, 0L));
        assertFalse(GenerationBenchmarkEvidenceProtocol.hasCurrentAttestation(
                legacy, GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE, 0L));
    }
}
