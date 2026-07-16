package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskQueryServiceDurableFallbackTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-16T06:00:00Z");

    private DurableGenerationTaskRepository repository;
    private GenerationTaskProgressEstimator progressEstimator;
    private GenerationTaskQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        progressEstimator = mock(GenerationTaskProgressEstimator.class);
        service = new GenerationTaskQueryService(
                new GenerationSessionRegistry(new GenerationSessionProperties()), repository, progressEstimator);
    }

    @Test
    void missingLocalSessionMustFallBackToDurableStatusAfterRestart() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        GenerationTaskProgressEstimate estimate = GenerationTaskProgressEstimate.unavailable(0L, SUBMITTED_AT);
        when(progressEstimator.estimate(
                "heavy_generation", "queued", SUBMITTED_AT, SUBMITTED_AT.plusSeconds(1_200), "queued"))
                .thenReturn(estimate);

        GenerationTaskSnapshot snapshot = service.get("task-durable", user(2L));

        assertEquals("queued", snapshot.status());
        assertEquals("heavy_generation", snapshot.route());
        assertEquals(SUBMITTED_AT, snapshot.submittedAt());
        assertEquals("queued", snapshot.stage());
        assertEquals("等待执行", snapshot.stageMessage());
        assertEquals(estimate, snapshot.progress());
        assertTrue(snapshot.usages().isEmpty());
        assertTrue(snapshot.limits().isEmpty());
    }

    @Test
    void durableFallbackMustStillEnforceOwnershipForStatusAndEvents() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));

        assertThrows(BusinessException.class, () -> service.get("task-durable", user(99L)));
        assertThrows(BusinessException.class, () -> service.events("task-durable", user(99L)));
    }

    @Test
    void eventsMustFailHonestlyWhenNoDurableEventLogExists() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));

        assertThrows(BusinessException.class, () -> service.events("task-durable", user(2L)));
    }

    private DurableGenerationTaskRecord record(long userId) {
        return new DurableGenerationTaskRecord(
                "task-durable", 1L, userId, "heavy_generation", GenerationTaskStatus.QUEUED, "queued", "等待执行",
                SUBMITTED_AT, SUBMITTED_AT.plusSeconds(1_200), false, null,
                "worker-a", SUBMITTED_AT.plusSeconds(30), SUBMITTED_AT,
                0, 0L, null, null);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
