package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationScenarioAttributionMapper;
import com.rush.rushaicodemother.mapper.GenerationScenarioBucketRow;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioAttribution;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketIdentity;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioBucketSummary;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCapacityMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioCostMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioLatencyMetrics;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioQualityMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationScenarioAttributionRepositoryTest {

    private static final String SIGNATURE = "a".repeat(64);
    private static final Instant FROM = Instant.parse("2026-07-01T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-08-01T00:00:00Z");

    private GenerationScenarioAttributionMapper mapper;
    private MyBatisGenerationScenarioAttributionRepository repository;

    @BeforeEach
    void setUp() {
        mapper = mock(GenerationScenarioAttributionMapper.class);
        repository = new MyBatisGenerationScenarioAttributionRepository(mapper);
    }

    @Test
    void invalidTaskIdentityMustReturnEmptyWithoutQueryingDatabase() {
        assertTrue(repository.findByTaskId("bad task id").isEmpty());
        assertTrue(repository.findByTaskId(null).isEmpty());

        verify(mapper, never()).selectByTaskId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void validTaskIdentityMustMapSingleTaskAttribution() {
        GenerationScenarioAttribution attribution = new GenerationScenarioAttribution(
                "task-1", 1L, SIGNATURE, "intent-profile-v1", "routing-policy-v1",
                "agent_edit", "routing-policy-v1@task-command-v7", "success", 5,
                "accepted", 3, 1, 0, 1_000L, 2_000L, 900L, 4L,
                2L, 0L, "openai", "gpt", "p", "t", "m",
                LocalDateTime.of(2026, 8, 1, 0, 0));
        when(mapper.selectByTaskId("task-1")).thenReturn(attribution);

        assertEquals(attribution, repository.findByTaskId("task-1").orElseThrow());
    }

    @Test
    void summaryQueryMustValidateSignatureWindowAndLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize(null, FROM, TO, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize("not-a-signature", FROM, TO, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize(SIGNATURE, TO, FROM, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize(SIGNATURE, FROM, FROM.plusSeconds(91L * 86_400), 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize(SIGNATURE, FROM, TO, 0));
        assertThrows(IllegalArgumentException.class,
                () -> repository.summarize(SIGNATURE, FROM, TO, 501));

        verify(mapper, never()).summarize(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void summaryQueryMustUseBoundedTimeRangeAndReturnImmutableResults() {
        GenerationScenarioBucketRow row = new GenerationScenarioBucketRow(
                SIGNATURE, "intent-profile-v1", "routing-policy-v1", "agent_edit",
                "b".repeat(64), 10L, 8L, 10L, 10L, 8L,
                10L, 2L, 5L, 1L, 4.2,
                10L, 1_000.0, 1_500L, 10L, 2_000.0, 3_000L,
                10L, 9_000L, 10L, 40L,
                10L, 12L, 2L, 1L);
        GenerationScenarioBucketSummary summary = new GenerationScenarioBucketSummary(
                new GenerationScenarioBucketIdentity(
                        SIGNATURE, "intent-profile-v1", "routing-policy-v1", "agent_edit",
                        "b".repeat(64)),
                new GenerationScenarioQualityMetrics(
                        10, 8, 10, 10, 8, 10, 2, 5, 1, 4.2),
                new GenerationScenarioLatencyMetrics(
                        10, 1_000.0, 1_500L, 10, 2_000.0, 3_000L),
                new GenerationScenarioCostMetrics(10, 9_000, 10, 40),
                new GenerationScenarioCapacityMetrics(10, 12, 2, 1));
        LocalDateTime localFrom = LocalDateTime.ofInstant(FROM, ZoneId.systemDefault());
        LocalDateTime localTo = LocalDateTime.ofInstant(TO, ZoneId.systemDefault());
        when(mapper.summarize(SIGNATURE, localFrom, localTo, 20)).thenReturn(List.of(row));

        List<GenerationScenarioBucketSummary> result = repository.summarize(
                SIGNATURE, FROM, TO, 20);

        assertEquals(List.of(summary), result);
        assertThrows(UnsupportedOperationException.class, () -> result.add(summary));
        verify(mapper).summarize(SIGNATURE, localFrom, localTo, 20);
    }
}
