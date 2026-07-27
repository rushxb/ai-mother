package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelConfiguration;
import com.rush.rushaicodemother.service.aimodel.AiModelConfigurationPolicy;
import com.rush.rushaicodemother.service.aimodel.AiModelPersistenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationBenchmarkAiModelConfigurationSnapshotTest {

    private GenerationBenchmarkWorkerCandidateProvider candidateProvider;
    private AiModelPersistenceService persistenceService;
    private AiModelConfigurationPolicy configurationPolicy;

    @BeforeEach
    void setUp() {
        candidateProvider = mock(GenerationBenchmarkWorkerCandidateProvider.class);
        persistenceService = mock(AiModelPersistenceService.class);
        configurationPolicy = mock(AiModelConfigurationPolicy.class);
    }

    @Test
    void modelCandidateMustBeEnabledSortedAndFrozenOnce() {
        AiModelConfiguration existing = model(1L, 10, 1);
        AiModelConfiguration disabled = model(7L, 20, 0);
        AiModelConfiguration normalized = model(7L, 20, 1);
        when(candidateProvider.candidate()).thenReturn(
                new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L));
        when(persistenceService.findEnabled(null)).thenReturn(List.of(existing));
        when(persistenceService.findActiveById(7L)).thenReturn(disabled);
        when(configurationPolicy.normalizeAndValidate(any())).thenReturn(normalized);
        GenerationBenchmarkAiModelConfigurationSnapshot snapshot = snapshot();

        List<AiModelConfiguration> first = snapshot.enabledModels();
        List<AiModelConfiguration> second = snapshot.enabledModels();

        assertEquals(List.of(7L, 1L), first.stream()
                .map(AiModelConfiguration::getId)
                .toList());
        assertEquals(first, second);
        assertEquals(1, first.getFirst().getIsEnabled());
        verify(persistenceService).findEnabled(null);
        verify(persistenceService).findActiveById(7L);
    }

    @Test
    void promptCandidateMustFreezeOnlyCurrentlyEnabledModels() {
        AiModelConfiguration existing = model(1L, 1, 1);
        when(candidateProvider.candidate()).thenReturn(
                new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                        "test-prompt", new PromptReleaseSpec("v1", "", 0)));
        when(persistenceService.findEnabled(null)).thenReturn(List.of(existing));

        assertEquals(List.of(existing), snapshot().enabledModels());
        verify(persistenceService, never()).findActiveById(org.mockito.ArgumentMatchers.anyLong());
    }

    private GenerationBenchmarkAiModelConfigurationSnapshot snapshot() {
        return new GenerationBenchmarkAiModelConfigurationSnapshot(
                candidateProvider, persistenceService, configurationPolicy);
    }

    private AiModelConfiguration model(long id, int sortOrder, int enabled) {
        return AiModelConfiguration.builder()
                .id(id)
                .sortOrder(sortOrder)
                .isEnabled(enabled)
                .modelType("chat")
                .build();
    }
}
