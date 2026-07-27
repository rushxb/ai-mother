package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BuildGenerationReleaseProvenanceProviderTest {

    private static final String COMMIT = "a".repeat(40);
    private static final String RUNTIME = "b".repeat(64);

    private GenerationGitBuildMetadataProvider gitBuildMetadataProvider;
    private GenerationRuntimeConfigurationFingerprintService runtimeFingerprintService;
    private BuildGenerationReleaseProvenanceProvider provider;

    @BeforeEach
    void setUp() {
        gitBuildMetadataProvider = mock(GenerationGitBuildMetadataProvider.class);
        runtimeFingerprintService = mock(GenerationRuntimeConfigurationFingerprintService.class);
        when(runtimeFingerprintService.currentFingerprint()).thenReturn(RUNTIME);
        provider = new BuildGenerationReleaseProvenanceProvider(
                gitBuildMetadataProvider,
                runtimeFingerprintService
        );
    }

    @Test
    void cleanBuildMetadataMustProduceManifest() {
        when(gitBuildMetadataProvider.current()).thenReturn(
                new GenerationGitBuildMetadataProvider.BuildMetadata(COMMIT, false));

        GenerationReleaseProvenanceManifest manifest = provider.current();

        assertEquals(COMMIT, manifest.gitCommit());
        assertEquals(RUNTIME, manifest.runtimeConfigFingerprint());
    }

    @Test
    void dirtyBuildMustFailClosed() {
        when(gitBuildMetadataProvider.current()).thenReturn(
                new GenerationGitBuildMetadataProvider.BuildMetadata(COMMIT, true));

        assertThrows(BusinessException.class, provider::current);
    }
}
