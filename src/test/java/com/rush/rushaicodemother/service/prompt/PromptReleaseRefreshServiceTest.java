package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.monitor.PromptReleaseMetricsCollector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PromptReleaseRefreshServiceTest {

    @Test
    void refreshMustLoadAndAtomicallyActivateDurableState() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.getRuntimeReleases().setEnabled(true);
        PromptReleaseRepository repository = mock(PromptReleaseRepository.class);
        PromptReleaseRuntime runtime = mock(PromptReleaseRuntime.class);
        PromptReleaseState state = new PromptReleaseState(4L, java.util.Map.of());
        when(repository.loadCurrent()).thenReturn(state);
        when(runtime.activate(state)).thenReturn(true);
        when(runtime.activeRevision()).thenReturn(4L);
        PromptReleaseRefreshService service = service(properties, repository, runtime);

        assertEquals(PromptReleaseRefreshResult.ACTIVATED, service.refreshNow());
        verify(repository).loadCurrent();
        verify(runtime).activate(state);
    }

    @Test
    void disabledRuntimeReleasesMustNotTouchPersistence() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.getRuntimeReleases().setEnabled(false);
        PromptReleaseRepository repository = mock(PromptReleaseRepository.class);
        PromptReleaseRuntime runtime = mock(PromptReleaseRuntime.class);
        PromptReleaseRefreshService service = service(properties, repository, runtime);

        assertEquals(PromptReleaseRefreshResult.DISABLED, service.refreshNow());
        service.loadInitialReleaseState();

        verify(repository, never()).loadCurrent();
        verify(runtime, never()).activate(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void productionInitialLoadMustFailClosedButDevelopmentMayKeepPackagedRelease() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.getRuntimeReleases().setEnabled(true);
        PromptReleaseRepository repository = mock(PromptReleaseRepository.class);
        PromptReleaseRuntime runtime = mock(PromptReleaseRuntime.class);
        when(repository.loadCurrent()).thenThrow(new IllegalStateException("database unavailable"));
        PromptReleaseRefreshService service = service(properties, repository, runtime);

        assertDoesNotThrow(service::loadInitialReleaseState);

        properties.getRuntimeReleases().setInitialLoadRequired(true);
        assertThrows(IllegalStateException.class, service::loadInitialReleaseState);
    }

    private PromptReleaseRefreshService service(AiPromptCatalogProperties properties,
                                                PromptReleaseRepository repository,
                                                PromptReleaseRuntime runtime) {
        return new PromptReleaseRefreshService(
                properties,
                repository,
                runtime,
                PromptReleaseMetricsCollector.noOp()
        );
    }
}
