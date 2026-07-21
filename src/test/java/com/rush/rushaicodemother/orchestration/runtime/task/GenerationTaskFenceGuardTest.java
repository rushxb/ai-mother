package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskFenceGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-18T05:00:00Z");
    private static final GenerationExecutionFence FENCE =
            new GenerationExecutionFence("task-1", "worker-a", 3L);

    private DurableGenerationTaskRepository repository;
    private GenerationExecutionContextService executionContextService;
    private GenerationTaskFenceGuard guard;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        guard = new GenerationTaskFenceGuard(
                repository,
                executionContextService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void unmanagedLegacyTaskMustRemainCompatibleWithoutARepositoryLookup() {
        when(executionContextService.getExecutionFence("legacy-task"))
                .thenReturn(Optional.empty());
        when(repository.findByTaskId("legacy-task")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> guard.assertCurrent("legacy-task"));

        verify(repository).findByTaskId("legacy-task");
    }

    @Test
    void durableTaskWithoutALocalFenceMustBeRejectedInsteadOfUsingLegacyCompatibility() {
        when(executionContextService.getExecutionFence("task-1"))
                .thenReturn(Optional.empty());
        when(repository.findByTaskId("task-1"))
                .thenReturn(Optional.of(mock(DurableGenerationTaskRecord.class)));

        assertThrows(GenerationExecutionPolicyException.class,
                () -> guard.assertCurrent("task-1"));

        verify(executionContextService).cancelByTaskId("task-1", "worker_fence_missing");
    }

    @Test
    void currentFenceMustPermitTheWorkspaceSideEffectBoundary() {
        when(executionContextService.getExecutionFence("task-1"))
                .thenReturn(Optional.of(FENCE));
        when(repository.isCurrentFence(FENCE, NOW)).thenReturn(true);

        assertDoesNotThrow(() -> guard.assertCurrent("task-1"));

        verify(executionContextService, never()).cancelByTaskId("task-1", "worker_fence_rejected");
    }

    @Test
    void staleFenceMustCancelTheLocalContextAndFailClosed() {
        when(executionContextService.getExecutionFence("task-1"))
                .thenReturn(Optional.of(FENCE));
        when(repository.isCurrentFence(FENCE, NOW)).thenReturn(false);

        assertThrows(GenerationExecutionPolicyException.class,
                () -> guard.assertCurrent("task-1"));

        verify(executionContextService).cancelByTaskId("task-1", "worker_fence_rejected");
    }

    @Test
    void repositoryFailureMustPropagateInsteadOfDowngradingToAnUnfencedWrite() {
        when(executionContextService.getExecutionFence("task-1"))
                .thenReturn(Optional.of(FENCE));
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(repository).isCurrentFence(FENCE, NOW);

        IllegalStateException thrown = assertThrows(
                IllegalStateException.class,
                () -> guard.assertCurrent("task-1"));

        assertSame(failure, thrown);
    }
}
