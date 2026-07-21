package com.rush.rushaicodemother.monitor.latency;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskLatencyLedgerServiceTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-18T01:00:00Z");
    private static final Instant CALCULATED_AT = SUBMITTED_AT.plusSeconds(120);

    private DurableGenerationTaskRepository taskRepository;
    private GenerationSpanQueryService spanQueryService;
    private GenerationTaskLatencyLedgerService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(DurableGenerationTaskRepository.class);
        spanQueryService = mock(GenerationSpanQueryService.class);
        service = new GenerationTaskLatencyLedgerService(
                taskRepository,
                spanQueryService,
                Clock.fixed(CALCULATED_AT, ZoneOffset.UTC)
        );
    }

    @Test
    void ledgerMustAttributeNestedSpansWithoutDoubleCountingWallClockTime() {
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task(
                GenerationTaskStatus.SUCCESS,
                SUBMITTED_AT.plusSeconds(100),
                SUBMITTED_AT.plusSeconds(90)
        )));
        when(spanQueryService.findByTaskId("task-1", GenerationSpanQueryService.MAX_LIMIT))
                .thenReturn(List.of(
                        span("queue", "durable_queue_wait", 0, 10),
                        span("pipeline", "generation", 10, 90),
                        span("model", "model_call", 20, 50),
                        span("tool", "tool_call", 50, 60),
                        span("build", "pnpm_build", 60, 80)
                ));

        GenerationTaskLatencyLedger ledger = service.getLedger("task-1");

        assertEquals(100_000L, ledger.totalLatencyMs());
        assertEquals(90_000L, ledger.attributedLatencyMs());
        assertEquals(10_000L, ledger.unattributedLatencyMs());
        assertEquals(90.0d, ledger.attributionCoveragePercent());
        assertEquals(60_000L, ledger.overlappingLatencyMs());
        assertEquals(10_000L, ledger.deadlineOvershootMs());
        assertEquals(5, ledger.spanCount());
        assertEquals(5, ledger.usableSpanCount());
        assertFalse(ledger.spanLimitReached());
        assertEquals("model", ledger.dominantCategory());
        assertCategory(ledger, "model", 30_000L, 30_000L);
        assertCategory(ledger, "pipeline", 20_000L, 80_000L);
        assertCategory(ledger, "queue", 10_000L, 10_000L);
        assertCategory(ledger, "tool", 10_000L, 10_000L);
        assertCategory(ledger, "build", 20_000L, 20_000L);
    }

    @Test
    void ledgerMustMergeSameCategoryOverlapAndClipSpansToTaskWindow() {
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task(
                GenerationTaskStatus.SUCCESS,
                SUBMITTED_AT.plusSeconds(100),
                null
        )));
        when(spanQueryService.findByTaskId("task-1", GenerationSpanQueryService.MAX_LIMIT))
                .thenReturn(List.of(
                        span("MODEL", "call-a", -10, 40),
                        span("model", "call-b", 30, 110)
                ));

        GenerationTaskLatencyLedger ledger = service.getLedger("task-1");

        assertEquals(100_000L, ledger.attributedLatencyMs());
        assertEquals(0L, ledger.unattributedLatencyMs());
        assertEquals(0L, ledger.overlappingLatencyMs());
        assertCategory(ledger, "model", 100_000L, 100_000L);
    }

    @Test
    void nonTerminalLedgerMustUseCurrentClockWithoutInventingCompletion() {
        when(taskRepository.findByTaskId("task-1")).thenReturn(Optional.of(task(
                GenerationTaskStatus.RUNNING,
                null,
                SUBMITTED_AT.plusSeconds(90)
        )));
        when(spanQueryService.findByTaskId("task-1", GenerationSpanQueryService.MAX_LIMIT))
                .thenReturn(List.of());

        GenerationTaskLatencyLedger ledger = service.getLedger("task-1");

        assertEquals(120_000L, ledger.totalLatencyMs());
        assertEquals(30_000L, ledger.deadlineOvershootMs());
        assertEquals(CALCULATED_AT, ledger.calculatedAt());
        assertEquals(null, ledger.completedAt());
        assertEquals("unattributed", ledger.dominantCategory());
    }

    @Test
    void missingTaskMustFailBeforeQueryingUnscopedSpans() {
        when(taskRepository.findByTaskId("missing")).thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getLedger("missing")
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    private DurableGenerationTaskRecord task(GenerationTaskStatus status,
                                             Instant completedAt,
                                             Instant deadlineAt) {
        return new DurableGenerationTaskRecord(
                "task-1", 1L, 2L, 100L, "heavy_generation", status, "build", "building",
                SUBMITTED_AT, deadlineAt, false, null, null, null, null,
                1, 3, completedAt, null
        );
    }

    private GenerationSpanQueryService.StoredSpan span(String category,
                                                       String stage,
                                                       long startOffsetSeconds,
                                                       long endOffsetSeconds) {
        Instant startedAt = SUBMITTED_AT.plusSeconds(startOffsetSeconds);
        Instant endedAt = SUBMITTED_AT.plusSeconds(endOffsetSeconds);
        return new GenerationSpanQueryService.StoredSpan(
                stage + "-id", "task-1", stage, category, "success",
                startedAt, endedAt, Math.max(0, endedAt.toEpochMilli() - startedAt.toEpochMilli()), ""
        );
    }

    private void assertCategory(GenerationTaskLatencyLedger ledger,
                                String category,
                                long attributedDurationMs,
                                long inclusiveDurationMs) {
        GenerationTaskLatencyLedger.CategoryLatency value = ledger.categories().stream()
                .filter(candidate -> category.equals(candidate.category()))
                .findFirst()
                .orElseThrow();
        assertEquals(attributedDurationMs, value.attributedDurationMs());
        assertEquals(inclusiveDurationMs, value.inclusiveDurationMs());
    }
}
