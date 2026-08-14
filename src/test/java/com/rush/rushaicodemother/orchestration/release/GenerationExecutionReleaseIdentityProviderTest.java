package com.rush.rushaicodemother.orchestration.release;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkModelFingerprintProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationBenchmarkPromptFingerprintProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationGitBuildMetadataProvider;
import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationRuntimeConfigurationFingerprintService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationExecutionReleaseIdentityProviderTest {

    private static final String GIT_COMMIT = "a".repeat(40);
    private static final String RUNTIME_POLICY = "b".repeat(64);
    private static final String PROMPT_BUNDLE = "c".repeat(64);
    private static final String MODEL_FLEET = "d".repeat(64);

    private GenerationGitBuildMetadataProvider buildMetadataProvider;
    private GenerationRuntimeConfigurationFingerprintService runtimeFingerprintService;
    private GenerationBenchmarkPromptFingerprintProvider promptFingerprintProvider;
    private GenerationBenchmarkModelFingerprintProvider modelFingerprintProvider;
    private GenerationExecutionReleaseIdentityProvider identityProvider;

    @BeforeEach
    void setUp() {
        buildMetadataProvider = mock(GenerationGitBuildMetadataProvider.class);
        runtimeFingerprintService = mock(GenerationRuntimeConfigurationFingerprintService.class);
        promptFingerprintProvider = mock(GenerationBenchmarkPromptFingerprintProvider.class);
        modelFingerprintProvider = mock(GenerationBenchmarkModelFingerprintProvider.class);
        when(buildMetadataProvider.current()).thenReturn(
                new GenerationGitBuildMetadataProvider.BuildMetadata(GIT_COMMIT, false));
        when(runtimeFingerprintService.currentFingerprint()).thenReturn(RUNTIME_POLICY);
        when(promptFingerprintProvider.currentRuntimeFingerprint()).thenReturn(PROMPT_BUNDLE);
        when(modelFingerprintProvider.currentFingerprint()).thenReturn(MODEL_FLEET);
        identityProvider = new GenerationExecutionReleaseIdentityProvider(
                buildMetadataProvider,
                runtimeFingerprintService,
                promptFingerprintProvider,
                modelFingerprintProvider);
    }

    @Test
    void currentMustExposeAnAttributableIdentityFromActualRuntimeFacts() {
        GenerationExecutionReleaseIdentity identity =
                identityProvider.current("intent-lexical/1.1.0");

        assertEquals(GIT_COMMIT, identity.gitCommit());
        assertFalse(identity.dirtyBuild());
        assertEquals(RUNTIME_POLICY, identity.runtimePolicyFingerprint());
        assertEquals(PROMPT_BUNDLE, identity.promptBundleFingerprint());
        assertEquals(MODEL_FLEET, identity.modelFleetFingerprint());
        assertEquals("intent-lexical/1.1.0", identity.decisionRuleVersion());
        assertEquals(64, identity.decisionPolicyFingerprint().length());
        assertEquals(64, identity.releaseFingerprint().length());
        assertEquals(identity, identityProvider.current("intent-lexical/1.1.0"));
    }

    @Test
    void everyReleaseFactMustParticipateInTheFingerprint() {
        String baseline = identityProvider.current("intent-lexical/1.1.0").releaseFingerprint();

        Set<String> changed = Set.of(
                fingerprint("e".repeat(40), false, RUNTIME_POLICY,
                        PROMPT_BUNDLE, MODEL_FLEET, "intent-lexical/1.1.0"),
                fingerprint(GIT_COMMIT, false, "f".repeat(64),
                        PROMPT_BUNDLE, MODEL_FLEET, "intent-lexical/1.1.0"),
                fingerprint(GIT_COMMIT, false, RUNTIME_POLICY,
                        "1".repeat(64), MODEL_FLEET, "intent-lexical/1.1.0"),
                fingerprint(GIT_COMMIT, false, RUNTIME_POLICY,
                        PROMPT_BUNDLE, "2".repeat(64), "intent-lexical/1.1.0"),
                fingerprint(GIT_COMMIT, false, RUNTIME_POLICY,
                        PROMPT_BUNDLE, MODEL_FLEET, "intent-lexical/2.0.0"),
                fingerprint(GIT_COMMIT, true, RUNTIME_POLICY,
                        PROMPT_BUNDLE, MODEL_FLEET, "intent-lexical/1.1.0"));

        assertEquals(6, changed.size());
        changed.forEach(fingerprint -> org.junit.jupiter.api.Assertions.assertNotEquals(baseline, fingerprint));
    }

    @Test
    void malformedRuntimeFactsMustFailClosed() {
        when(promptFingerprintProvider.currentRuntimeFingerprint()).thenReturn("prompt-v1");

        assertThrows(IllegalArgumentException.class,
                () -> identityProvider.current("intent-lexical/1.1.0"));

        when(promptFingerprintProvider.currentRuntimeFingerprint()).thenReturn(PROMPT_BUNDLE);
        assertThrows(IllegalArgumentException.class, () -> identityProvider.current(" "));
    }

    private String fingerprint(String gitCommit,
                               boolean dirtyBuild,
                               String runtimePolicy,
                               String promptBundle,
                               String modelFleet,
                               String ruleVersion) {
        GenerationGitBuildMetadataProvider buildProvider =
                mock(GenerationGitBuildMetadataProvider.class);
        GenerationRuntimeConfigurationFingerprintService runtimeProvider =
                mock(GenerationRuntimeConfigurationFingerprintService.class);
        GenerationBenchmarkPromptFingerprintProvider promptProvider =
                mock(GenerationBenchmarkPromptFingerprintProvider.class);
        GenerationBenchmarkModelFingerprintProvider modelProvider =
                mock(GenerationBenchmarkModelFingerprintProvider.class);
        when(buildProvider.current()).thenReturn(
                new GenerationGitBuildMetadataProvider.BuildMetadata(gitCommit, dirtyBuild));
        when(runtimeProvider.currentFingerprint()).thenReturn(runtimePolicy);
        when(promptProvider.currentRuntimeFingerprint()).thenReturn(promptBundle);
        when(modelProvider.currentFingerprint()).thenReturn(modelFleet);
        return new GenerationExecutionReleaseIdentityProvider(
                buildProvider, runtimeProvider, promptProvider, modelProvider)
                .current(ruleVersion)
                .releaseFingerprint();
    }
}
