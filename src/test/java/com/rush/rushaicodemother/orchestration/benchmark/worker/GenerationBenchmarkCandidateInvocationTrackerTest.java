package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationBenchmarkCandidateInvocationTrackerTest {

    @Test
    void modelCandidateMustReceiveARealRequest() {
        GenerationBenchmarkWorkerCandidateProvider candidateProvider =
                mock(GenerationBenchmarkWorkerCandidateProvider.class);
        AiModelEnabledConfigurationSnapshot snapshot =
                mock(AiModelEnabledConfigurationSnapshot.class);
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L);
        when(candidateProvider.candidate()).thenReturn(candidate);
        when(snapshot.enabledModels()).thenReturn(List.of(AiModelConfiguration.builder()
                .id(7L)
                .provider("provider")
                .modelId("candidate-model")
                .build()));
        GenerationBenchmarkCandidateInvocationTracker tracker =
                new GenerationBenchmarkCandidateInvocationTracker(candidateProvider, snapshot);

        tracker.onRequest("provider", "candidate-model");
        tracker.begin(candidate);
        tracker.onRequest("provider", "other-model");
        assertThrows(IllegalStateException.class,
                () -> tracker.requireCandidateInvoked(candidate));

        tracker.onRequest("provider", "candidate-model");
        assertEquals(1L, tracker.requireCandidateInvoked(candidate));
        assertEquals(1L, tracker.candidateRequestCount());
        tracker.end();
        tracker.onRequest("provider", "candidate-model");
        assertEquals(1L, tracker.candidateRequestCount());
        assertThrows(IllegalStateException.class,
                () -> tracker.requireCandidateInvoked(candidate));
    }

    @Test
    void promptCandidateMustNotRequireOneSpecificModel() {
        GenerationBenchmarkWorkerCandidateProvider candidateProvider =
                mock(GenerationBenchmarkWorkerCandidateProvider.class);
        AiModelEnabledConfigurationSnapshot snapshot =
                mock(AiModelEnabledConfigurationSnapshot.class);
        GenerationBenchmarkEvidenceCandidate candidate =
                new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                        "test-prompt", new PromptReleaseSpec("v1", "", 0));
        when(candidateProvider.candidate()).thenReturn(candidate);
        GenerationBenchmarkCandidateInvocationTracker tracker =
                new GenerationBenchmarkCandidateInvocationTracker(candidateProvider, snapshot);

        tracker.begin(candidate);
        assertEquals(0L, tracker.requireCandidateInvoked(candidate));
        tracker.end();
    }
}
