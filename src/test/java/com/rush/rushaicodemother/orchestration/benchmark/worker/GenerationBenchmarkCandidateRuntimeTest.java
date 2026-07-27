package com.rush.rushaicodemother.orchestration.benchmark.worker;

import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkEvidenceCandidate;
import com.rush.rushaicodemother.service.aimodel.AiModelEnabledConfigurationSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationBenchmarkCandidateRuntimeTest {

    @Test
    void prepareMustFreezeModelPoolAndActivatePromptCandidateAtMaximumRevision() {
        AiModelEnabledConfigurationSnapshot modelSnapshot =
                mock(AiModelEnabledConfigurationSnapshot.class);
        PromptReleaseRepository repository = mock(PromptReleaseRepository.class);
        PromptReleaseRuntime runtime = mock(PromptReleaseRuntime.class);
        PromptCatalog catalog = mock(PromptCatalog.class);
        when(repository.loadCurrent()).thenReturn(PromptReleaseState.empty());
        PromptCatalogSnapshot expected = mock(PromptCatalogSnapshot.class);
        when(runtime.preview(any())).thenReturn(expected);
        when(catalog.snapshot()).thenReturn(expected);
        GenerationBenchmarkCandidateRuntime candidateRuntime =
                new GenerationBenchmarkCandidateRuntime(
                        modelSnapshot, repository, runtime, catalog);
        PromptReleaseSpec release = new PromptReleaseSpec("v2", "", 0);

        candidateRuntime.prepare(
                new GenerationBenchmarkEvidenceCandidate.PromptRelease(
                        "test-prompt", release));

        verify(modelSnapshot).enabledModels();
        ArgumentCaptor<PromptReleaseState> stateCaptor =
                ArgumentCaptor.forClass(PromptReleaseState.class);
        verify(runtime).activate(stateCaptor.capture());
        PromptReleaseState frozen = stateCaptor.getValue();
        assertEquals(Long.MAX_VALUE, frozen.revision());
        assertSame(release, frozen.releases().get("test-prompt").release());
        assertEquals(Map.of("test-prompt", frozen.releases().get("test-prompt")),
                frozen.releases());
    }
}
