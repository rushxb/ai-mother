package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.benchmark.evidence.GenerationReleaseProvenanceProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GenerationReleaseProvenanceReadinessVerifierTest {

    @Test
    void productionMustVerifyBuildProvenanceBeforeReadiness() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        GenerationReleaseProvenanceProvider provider =
                mock(GenerationReleaseProvenanceProvider.class);

        new GenerationReleaseProvenanceReadinessVerifier(environment, provider)
                .afterSingletonsInstantiated();

        verify(provider).current();
    }

    @Test
    void developmentStartupMustNotRequireReleaseMetadata() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("dev");
        GenerationReleaseProvenanceProvider provider =
                mock(GenerationReleaseProvenanceProvider.class);

        new GenerationReleaseProvenanceReadinessVerifier(environment, provider)
                .afterSingletonsInstantiated();

        verifyNoInteractions(provider);
    }
}
