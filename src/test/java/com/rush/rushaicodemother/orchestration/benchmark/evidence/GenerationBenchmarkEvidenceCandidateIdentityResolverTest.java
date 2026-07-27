package com.rush.rushaicodemother.orchestration.benchmark.evidence;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationBenchmarkEvidenceCandidateIdentityResolverTest {

    private final GenerationBenchmarkEvidenceCandidate candidate =
            new GenerationBenchmarkEvidenceCandidate.AiModelEnable(7L);

    @Test
    void resolveMustFailClosedWhenNoStrategyMatches() {
        GenerationBenchmarkEvidenceCandidateResolver strategy = strategy(false);
        GenerationBenchmarkEvidenceCandidateIdentityResolver resolver =
                new GenerationBenchmarkEvidenceCandidateIdentityResolver(List.of(strategy));

        assertThrows(BusinessException.class, () -> resolver.resolve(candidate));
        verify(strategy, never()).resolve(candidate);
    }

    @Test
    void resolveMustFailClosedWhenMultipleStrategiesMatch() {
        GenerationBenchmarkEvidenceCandidateResolver first = strategy(true);
        GenerationBenchmarkEvidenceCandidateResolver second = strategy(true);
        GenerationBenchmarkEvidenceCandidateIdentityResolver resolver =
                new GenerationBenchmarkEvidenceCandidateIdentityResolver(List.of(first, second));

        assertThrows(IllegalStateException.class, () -> resolver.resolve(candidate));
        verify(first, never()).resolve(candidate);
        verify(second, never()).resolve(candidate);
    }

    @Test
    void resolveMustReturnTheOnlyMatchingStrategyResult() {
        GenerationBenchmarkEvidenceCandidateResolver ignored = strategy(false);
        GenerationBenchmarkEvidenceCandidateResolver matched = strategy(true);
        GenerationBenchmarkEvidenceCandidateIdentity expected =
                new GenerationBenchmarkEvidenceCandidateIdentity(
                        GenerationBenchmarkEvidenceSubject.AI_MODEL_ENABLE,
                        "7",
                        "a".repeat(64),
                        "b".repeat(64),
                        "c".repeat(64)
                );
        when(matched.resolve(candidate)).thenReturn(expected);
        GenerationBenchmarkEvidenceCandidateIdentityResolver resolver =
                new GenerationBenchmarkEvidenceCandidateIdentityResolver(List.of(ignored, matched));

        assertSame(expected, resolver.resolve(candidate));
        verify(matched).resolve(candidate);
    }

    private GenerationBenchmarkEvidenceCandidateResolver strategy(boolean supports) {
        GenerationBenchmarkEvidenceCandidateResolver strategy =
                mock(GenerationBenchmarkEvidenceCandidateResolver.class);
        when(strategy.supports(candidate)).thenReturn(supports);
        return strategy;
    }
}
