package com.rush.rushaicodemother.ai.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRecord;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseSpec;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import dev.langchain4j.invocation.InvocationContext;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClasspathPromptCatalogTest {

    @Test
    void defaultReleaseMustSelectAndIdentifyImmutableStableVersion() {
        ClasspathPromptCatalog catalog = catalog(properties());

        PromptSelection selection = catalog.select(subject("tenant-1")).orElseThrow();

        assertEquals("test-prompt", selection.promptKey());
        assertEquals("v1", selection.version());
        assertEquals(PromptSelection.Channel.STABLE, selection.channel());
        assertEquals("stable prompt", selection.content());
        assertEquals(64, selection.contentHash().length());
        assertEquals(64, catalog.bundleId().length());
        assertTrue(catalog.snapshot().managed());
        assertEquals(selection.version(), catalog.identify(selection.content()).orElseThrow().version());
    }

    @Test
    void canarySelectionMustBeDeterministicAndRollbackMustChangeBundle() {
        AiPromptCatalogProperties canaryProperties = properties();
        AiPromptCatalogProperties.Release release = new AiPromptCatalogProperties.Release();
        release.setStableVersion("v1");
        release.setCanaryVersion("v2");
        release.setCanaryPercentage(50);
        canaryProperties.getReleases().put("test-prompt", release);
        ClasspathPromptCatalog canaryCatalog = catalog(canaryProperties);

        EnumSet<PromptSelection.Channel> channels = EnumSet.noneOf(PromptSelection.Channel.class);
        for (int index = 0; index < 500; index++) {
            PromptRolloutSubject subject = subject("tenant-" + index);
            PromptSelection first = canaryCatalog.select(subject).orElseThrow();
            PromptSelection second = canaryCatalog.select(subject).orElseThrow();
            assertEquals(first, second);
            channels.add(first.channel());
        }
        assertTrue(channels.contains(PromptSelection.Channel.STABLE));
        assertTrue(channels.contains(PromptSelection.Channel.CANARY));

        AiPromptCatalogProperties rollbackProperties = properties();
        AiPromptCatalogProperties.Release rollback = new AiPromptCatalogProperties.Release();
        rollback.setStableVersion("v2");
        rollbackProperties.getReleases().put("test-prompt", rollback);
        ClasspathPromptCatalog rollbackCatalog = catalog(rollbackProperties);

        assertEquals("v2", rollbackCatalog.select(subject("tenant-1")).orElseThrow().version());
        assertNotEquals(canaryCatalog.bundleId(), rollbackCatalog.bundleId());
    }

    @Test
    void manifestHashMismatchMustFailClosed() {
        AiPromptCatalogProperties properties = properties();
        properties.setManifest("classpath:prompt/invalid-prompt-catalog.json");

        assertThrows(IllegalStateException.class, () -> catalog(properties));
    }

    @Test
    void productionCatalogMustActivateBatchWritePromptsAndRetainV1Rollback() {
        AiPromptCatalogProperties properties = properties();
        properties.setManifest("classpath:prompt/prompt-catalog.json");
        PromptRolloutSubject vueGeneration = new PromptRolloutSubject(
                "com.rush.rushaicodemother.ai.AiCodeGeneratorService",
                "generateVueProjectCodeStream",
                "app-42"
        );
        PromptRolloutSubject backendGeneration = new PromptRolloutSubject(
                "com.rush.rushaicodemother.ai.AiCodeGeneratorService",
                "generateBackendProjectCodeStream",
                "app-42"
        );
        PromptRolloutSubject fullStackGeneration = new PromptRolloutSubject(
                "com.rush.rushaicodemother.ai.AiCodeGeneratorService",
                "generateFullStackProjectCodeStream",
                "app-42"
        );
        ClasspathPromptCatalog defaultCatalog = catalog(properties);

        PromptSelection defaultSelection = defaultCatalog.select(vueGeneration).orElseThrow();
        assertEquals("v2", defaultSelection.version());
        assertTrue(defaultSelection.content().contains("writeFiles"));
        assertEquals("v2", defaultCatalog.select(backendGeneration).orElseThrow().version());
        assertTrue(defaultCatalog.select(backendGeneration).orElseThrow().content().contains("writeFiles"));
        assertEquals("v2", defaultCatalog.select(fullStackGeneration).orElseThrow().version());
        assertTrue(defaultCatalog.select(fullStackGeneration).orElseThrow().content().contains("writeFiles"));
        assertTrue(defaultCatalog.capabilities().supports("codegen-vue-project", "v1"));
        assertTrue(defaultCatalog.capabilities().supports("codegen-vue-project", "v2"));
        assertTrue(defaultCatalog.capabilities().supports("codegen-backend-project", "v2"));
        assertTrue(defaultCatalog.capabilities().supports("codegen-full-stack-project", "v2"));

        AiPromptCatalogProperties.Release release = new AiPromptCatalogProperties.Release();
        release.setStableVersion("v1");
        properties.getReleases().put("codegen-vue-project", release);
        ClasspathPromptCatalog rollbackCatalog = catalog(properties);
        PromptSelection rollbackSelection = rollbackCatalog.select(vueGeneration).orElseThrow();
        assertEquals("v1", rollbackSelection.version());
        assertFalse(rollbackSelection.content().contains("writeFiles"));
    }

    @Test
    void transformerMustRejectBoundPromptOutsideCatalog() {
        ClasspathPromptCatalog catalog = catalog(properties());
        PromptSystemMessageTransformer transformer = new PromptSystemMessageTransformer(catalog);
        InvocationContext context = mock(InvocationContext.class);
        when(context.interfaceName()).thenReturn("com.example.TestService");
        when(context.methodName()).thenReturn("generate");
        when(context.chatMemoryId()).thenReturn(42L);

        assertEquals("stable prompt", transformer.transform("stable prompt", context));
        assertThrows(IllegalStateException.class,
                () -> transformer.transform("unregistered prompt", context));
    }

    @Test
    void disabledCatalogMustLeavePromptUnmanaged() {
        AiPromptCatalogProperties properties = properties();
        properties.setEnabled(false);
        ClasspathPromptCatalog catalog = catalog(properties);

        assertFalse(catalog.snapshot().managed());
        assertTrue(catalog.select(subject("tenant-1")).isEmpty());
    }

    @Test
    void durableReleaseMustSwapAtomicallyAndRejectStaleOrInvalidStates() {
        ClasspathPromptCatalog catalog = catalog(properties());
        String baselineBundle = catalog.bundleId();
        PromptReleaseState versionTwo = state(1L, new PromptReleaseSpec("v2", "", 0));

        assertTrue(catalog.activate(versionTwo));
        assertEquals(1L, catalog.activeRevision());
        assertEquals("v2", catalog.select(subject("tenant-1")).orElseThrow().version());
        assertNotEquals(baselineBundle, catalog.bundleId());
        assertTrue(catalog.capabilities().supports("test-prompt", "v1"));
        assertTrue(catalog.capabilities().supports("test-prompt", "v2"));

        assertFalse(catalog.activate(versionTwo));
        assertFalse(catalog.activate(PromptReleaseState.empty()));

        PromptReleaseState invalid = state(2L, new PromptReleaseSpec("v3", "", 0));
        assertThrows(IllegalStateException.class, () -> catalog.activate(invalid));
        assertEquals(1L, catalog.activeRevision());
        assertEquals("v2", catalog.select(subject("tenant-1")).orElseThrow().version());
    }

    @Test
    void previewMustNotMutateActiveStateAndMustMatchActivation() {
        ClasspathPromptCatalog catalog = catalog(properties());
        PromptCatalogSnapshot baseline = catalog.snapshot();
        PromptReleaseState versionTwo = state(1L, new PromptReleaseSpec("v2", "", 0));

        PromptCatalogSnapshot preview = catalog.preview(versionTwo);

        assertNotEquals(baseline, preview);
        assertEquals(0L, catalog.activeRevision());
        assertEquals(baseline, catalog.snapshot());
        assertEquals("v1", catalog.select(subject("tenant-1")).orElseThrow().version());

        assertTrue(catalog.activate(versionTwo));
        assertEquals(preview, catalog.snapshot());
        assertEquals("v2", catalog.select(subject("tenant-1")).orElseThrow().version());
    }

    private ClasspathPromptCatalog catalog(AiPromptCatalogProperties properties) {
        return new ClasspathPromptCatalog(
                properties,
                new DefaultResourceLoader(),
                new ObjectMapper()
        );
    }

    private AiPromptCatalogProperties properties() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        properties.setManifest("classpath:prompt/test-prompt-catalog.json");
        properties.setRolloutSalt("test-rollout-salt");
        return properties;
    }

    private PromptRolloutSubject subject(String cohort) {
        return new PromptRolloutSubject("com.example.TestService", "generate", cohort);
    }

    private PromptReleaseState state(long revision, PromptReleaseSpec release) {
        PromptReleaseRecord record = new PromptReleaseRecord(
                "test-prompt",
                release,
                revision,
                7L,
                "test release",
                Instant.parse("2026-07-17T00:00:00Z")
        );
        return new PromptReleaseState(revision, Map.of("test-prompt", record));
    }
}
