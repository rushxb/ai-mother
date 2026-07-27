package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.config.GenerationBenchmarkWorkerProperties;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class GenerationBenchmarkWorkerCandidateProviderTest {

    @Test
    void providerMustBuildPromptCandidateFromNormalizedConfiguration() {
        GenerationBenchmarkWorkerProperties properties =
                new GenerationBenchmarkWorkerProperties();
        properties.getCandidate().setSubjectType(" prompt_release ");
        properties.getCandidate().setPromptKey(" test-prompt ");
        properties.getCandidate().setStableVersion(" v1 ");
        properties.getCandidate().setCanaryVersion(" v2 ");
        properties.getCandidate().setCanaryPercentage(25);
        GenerationBenchmarkWorkerCandidateProvider provider =
                new GenerationBenchmarkWorkerCandidateProvider(properties);

        GenerationBenchmarkEvidenceCandidate.PromptRelease candidate = assertInstanceOf(
                GenerationBenchmarkEvidenceCandidate.PromptRelease.class,
                provider.candidate()
        );

        assertEquals("test-prompt", candidate.promptKey());
        assertEquals("v1", candidate.release().stableVersion());
        assertEquals("v2", candidate.release().canaryVersion());
        assertEquals(25, candidate.release().canaryPercentage());
    }
}
