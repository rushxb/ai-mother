package com.rush.rushaicodemother.service.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.ClasspathPromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptCatalogSnapshot;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PromptReleaseCandidateFingerprintServiceTest {

    @Test
    void candidateBundleMustMatchRealActivationWithoutMutatingActiveCatalog() {
        ClasspathPromptCatalog catalog = catalog();
        PromptReleaseRepository repository = mock(PromptReleaseRepository.class);
        when(repository.loadCurrent()).thenReturn(PromptReleaseState.empty());
        PromptReleaseCandidateFingerprintService service =
                new PromptReleaseCandidateFingerprintService(catalog, repository, catalog);
        PromptReleaseSpec candidate = new PromptReleaseSpec("v2", "", 0);
        PromptCatalogSnapshot baseline = catalog.snapshot();

        String candidateBundle = service.promptBundleFingerprint("test-prompt", candidate);

        assertEquals(baseline, catalog.snapshot());
        assertEquals(0L, catalog.activeRevision());

        PromptReleaseState target = new PromptReleaseState(1L, Map.of(
                "test-prompt",
                new PromptReleaseRecord(
                        "test-prompt",
                        candidate,
                        1L,
                        7L,
                        "发布候选",
                        Instant.parse("2026-07-17T00:00:00Z")
                )
        ));
        assertTrue(catalog.activate(target));
        assertEquals(candidateBundle, catalog.bundleId());
    }

    private ClasspathPromptCatalog catalog() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.setManifest("classpath:prompt/test-prompt-catalog.json");
        properties.setRolloutSalt("test-rollout-salt");
        return new ClasspathPromptCatalog(
                properties,
                new DefaultResourceLoader(),
                new ObjectMapper()
        );
    }
}
