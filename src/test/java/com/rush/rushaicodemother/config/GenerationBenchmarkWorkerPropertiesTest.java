package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkWorkerPropertiesTest {

    @Test
    void disabledWorkerMustNotRequireCandidateConfiguration() {
        assertTrue(new GenerationBenchmarkWorkerProperties().isConfigurationValid());
    }

    @Test
    void modelCandidateMustBeUnambiguous() {
        GenerationBenchmarkWorkerProperties properties = enabled();
        properties.getCandidate().setSubjectType("AI_MODEL_ENABLE");
        properties.getCandidate().setModelId(7L);

        assertTrue(properties.isConfigurationValid());

        properties.getCandidate().setPromptKey("test-prompt");
        assertFalse(properties.isConfigurationValid());
    }

    @Test
    void promptCandidateMustValidateCanaryContract() {
        GenerationBenchmarkWorkerProperties properties = enabled();
        properties.getCandidate().setSubjectType("PROMPT_RELEASE");
        properties.getCandidate().setPromptKey("test-prompt");
        properties.getCandidate().setStableVersion("v1");
        properties.getCandidate().setCanaryVersion("v2");
        properties.getCandidate().setCanaryPercentage(10);

        assertTrue(properties.isConfigurationValid());

        properties.getCandidate().setCanaryVersion("v1");
        assertFalse(properties.isConfigurationValid());
    }

    private GenerationBenchmarkWorkerProperties enabled() {
        GenerationBenchmarkWorkerProperties properties =
                new GenerationBenchmarkWorkerProperties();
        properties.setEnabled(true);
        properties.setOutputFile("target/benchmark-worker-result.json");
        return properties;
    }
}
