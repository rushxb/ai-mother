package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationModelStrategyPromotionArchitectureTest {

    private static final Path ROOT = Path.of("").toAbsolutePath().normalize();

    @Test
    void failoverAndHedgeMustRemainBoundToTheReleaseFingerprint() throws IOException {
        String fleetFingerprint = read(
                "service", "aimodel", "AiModelFleetFingerprintService.java");
        String releaseIdentity = read(
                "orchestration", "release", "GenerationExecutionReleaseIdentity.java");

        assertTrue(fleetFingerprint.contains("getFailoverMaxCandidates()"));
        assertTrue(fleetFingerprint.contains("isFirstTokenHedgeEnabled()"));
        assertTrue(fleetFingerprint.contains("getFirstTokenHedgeDelay()"));
        assertTrue(fleetFingerprint.contains("isFirstTokenHedgeRequireDistinctProvider()"));
        assertTrue(releaseIdentity.contains("runtimePolicyFingerprint"));
        assertTrue(releaseIdentity.contains("modelFleetFingerprint"));
        assertTrue(releaseIdentity.contains("decisionPolicyFingerprint()"));
    }

    @Test
    void successfulPhysicalAttemptMustNotMutateFutureCandidateOrder() throws IOException {
        String synchronous = read(
                "ai", "model", "failover", "FailoverChatModel.java");
        String streaming = read(
                "ai", "model", "failover", "FailoverStreamingChatModel.java");

        for (String source : new String[]{synchronous, streaming}) {
            assertFalse(source.contains("preferredCandidateIndex"));
            assertFalse(source.contains("stickyProvider"));
            assertFalse(source.contains("promote(candidateIndex)"));
        }
    }

    @Test
    void onePromotionGateMustAssessQualityLatencyBothCostsAndCapacity() throws IOException {
        String gate = read(
                "orchestration", "learning", "GenerationStrategyPromotionGate.java");

        assertTrue(gate.contains("assessRelativeQuality"));
        assertTrue(gate.contains("assessRelativeLatency"));
        assertTrue(gate.contains("assessRelativeCost"));
        assertTrue(gate.contains("assessRelativeCapacity"));
        assertTrue(gate.contains("_capacity_observation_incomplete"));
        assertTrue(gate.contains("physical_model_calls_per_success_regressed"));
        assertTrue(gate.contains("capacity_failure_rate_regressed"));
    }

    private String read(String... segments) throws IOException {
        Path path = ROOT.resolve(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother"));
        for (String segment : segments) {
            path = path.resolve(segment);
        }
        return Files.readString(path);
    }
}
