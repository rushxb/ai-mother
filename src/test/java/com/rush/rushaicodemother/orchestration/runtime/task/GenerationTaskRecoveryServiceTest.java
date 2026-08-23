package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.dag.AgentRuntimeState;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTaskStore;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalRepository;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalEntry;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalStatus;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T04:00:00Z");

    private DurableGenerationTaskRepository repository;
    private GenerationTaskFinalizer taskFinalizer;
    private GenerationWorkspacePublicationJournalRepository publicationJournal;
    private GenerationExecutionContextService executionContextService;
    private GenerationTaskRecoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        taskFinalizer = mock(GenerationTaskFinalizer.class);
        publicationJournal = mock(GenerationWorkspacePublicationJournalRepository.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setRecoveryBatchSize(25);
        service = new GenerationTaskRecoveryService(
                repository, properties, new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void expiredLeaseMustBeTerminalizedWithVersionCasAndReleaseOnlyOwnedAppState() {
        GenerationTaskRecoveryCandidate recovered = orphanCandidate("task-expired", 1L, 7L);
        GenerationTaskRecoveryCandidate raced = orphanCandidate("task-raced", 2L, 9L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(recovered, raced));
        when(taskFinalizer.finalizeExpiredLease(
                recovered, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(true);
        when(taskFinalizer.finalizeExpiredLease(
                raced, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(false);

        int count = service.recoverExpiredTasks();

        assertEquals(1, count);
        verify(executionContextService).cancelByTaskId(
                "task-expired", GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
        verify(taskFinalizer).finalizeExpiredLease(
                recovered, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
        verify(taskFinalizer).finalizeExpiredLease(
                raced, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void cancellationAndDeadlineSemanticsMustReachDurableTerminalization() {
        GenerationTaskRecoveryCandidate cancelled = new GenerationTaskRecoveryCandidate(
                "task-cancelled", 1L, GenerationTaskStatus.RUNNING, "lost-worker",
                NOW.minusSeconds(1), NOW.minusSeconds(30), true, "user_requested", 1L, 3L
        );
        GenerationTaskRecoveryCandidate deadline = new GenerationTaskRecoveryCandidate(
                "task-deadline", 2L, GenerationTaskStatus.RUNNING, "lost-worker",
                NOW.minusSeconds(1), NOW.minusSeconds(1), false, null, 1L, 4L
        );
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(cancelled, deadline));
        when(taskFinalizer.finalizeExpiredLease(
                cancelled, GenerationTaskStatus.CANCELLED, NOW, "user_requested"
        )).thenReturn(true);
        when(taskFinalizer.finalizeExpiredLease(
                deadline, GenerationTaskStatus.DEADLINE_EXCEEDED, NOW,
                GenerationTaskRecoveryPolicy.DEADLINE_EXCEEDED_REASON
        )).thenReturn(true);

        assertEquals(2, service.recoverExpiredTasks());

        verify(executionContextService).cancelByTaskId("task-cancelled", "user_requested");
        verify(executionContextService).cancelByTaskId(
                "task-deadline", GenerationTaskRecoveryPolicy.DEADLINE_EXCEEDED_REASON
        );
        verify(taskFinalizer).finalizeExpiredLease(
                cancelled, GenerationTaskStatus.CANCELLED, NOW, "user_requested");
        verify(taskFinalizer).finalizeExpiredLease(
                deadline, GenerationTaskStatus.DEADLINE_EXCEEDED, NOW,
                GenerationTaskRecoveryPolicy.DEADLINE_EXCEEDED_REASON);
    }

    @Test
    void oneRecoveryFailureMustNotPreventLaterCandidates() {
        GenerationTaskRecoveryCandidate broken = orphanCandidate("task-broken", 1L, 1L);
        GenerationTaskRecoveryCandidate healthy = orphanCandidate("task-healthy", 2L, 2L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(broken, healthy));
        when(taskFinalizer.finalizeExpiredLease(
                broken, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON))
                .thenThrow(new IllegalStateException("transient"));
        when(taskFinalizer.finalizeExpiredLease(
                healthy, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());
        verify(taskFinalizer).finalizeExpiredLease(
                healthy, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void committedPublicationMustRecoverExpiredTaskAsSuccess() {
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-published", 1L, 6L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(publicationJournal.findByTaskId("task-published")).thenReturn(Optional.of(
                publication(candidate, GenerationWorkspacePublicationJournalStatus.COMMITTED)));
        GenerationFinalizationCommand intent = GenerationFinalizationCommand.of(
                "task-published", 1L,
                new GenerationExecutionFence("task-published", "lost-worker", 1L),
                GenerationTaskStatus.SUCCESS, null, "完整记忆", null);
        when(repository.findFinalizationIntent("task-published", 1L))
                .thenReturn(Optional.of(intent));
        when(taskFinalizer.finalizeExpiredPublishedTask(candidate, intent, NOW)).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(taskFinalizer).finalizeExpiredPublishedTask(candidate, intent, NOW);
        verify(executionContextService, never()).cancelByTaskId(
                "task-published", GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void activatedPublicationMustWaitForJournalReconciliation() {
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-activating", 1L, 6L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(publicationJournal.findByTaskId("task-activating")).thenReturn(Optional.of(
                publication(candidate, GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED)));

        assertEquals(0, service.recoverExpiredTasks());

        verify(taskFinalizer, never()).finalizeExpiredLease(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void preparedPublicationMustWaitForJournalReconciliationBeforeChangingExecutionEpoch() {
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-prepared", 1L, 6L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(publicationJournal.findByTaskId("task-prepared")).thenReturn(Optional.of(
                publication(candidate, GenerationWorkspacePublicationJournalStatus.PREPARED)));

        assertEquals(0, service.recoverExpiredTasks());

        verify(repository, never()).requeueExpiredLease(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
        verify(taskFinalizer, never()).finalizeExpiredLease(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void expiredRunningTaskWithCheckpointMustBeRequeuedAndDispatchedForResume() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationTaskDispatcher dispatcher = mock(GenerationTaskDispatcher.class);
        service = new GenerationTaskRecoveryService(
                repository, properties(), new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService, null, null,
                taskStore, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-resume", 1L, 7L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        GenerationOrchestrationTask checkpoint = new GenerationOrchestrationTask();
        checkpoint.setTaskId("task-resume");
        checkpoint.setAppId(1L);
        checkpoint.setExecutionEpoch(1L);
        checkpoint.setStatus("running");
        when(taskStore.load(1L, "task-resume")).thenReturn(Optional.of(checkpoint));
        when(repository.requeueExpiredLease(candidate, NOW, "checkpoint_resume")).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(repository).requeueExpiredLease(candidate, NOW, "checkpoint_resume");
        verify(dispatcher).dispatch("task-resume");
        verify(taskFinalizer, never()).finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void completedPreparationCheckpointMustBeRequeuedForModelPhaseResume() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationTaskDispatcher dispatcher = mock(GenerationTaskDispatcher.class);
        service = new GenerationTaskRecoveryService(
                repository, properties(), new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService, null, null,
                taskStore, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-completed-dag", 1L, 8L);
        GenerationOrchestrationTask checkpoint = new GenerationOrchestrationTask();
        checkpoint.setTaskId("task-completed-dag");
        checkpoint.setAppId(1L);
        checkpoint.setExecutionEpoch(1L);
        checkpoint.setStatus("completed");
        checkpoint.setRuntimeState(AgentRuntimeState.COMPLETED);
        checkpoint.setDagFingerprint("a".repeat(64));
        checkpoint.setLastCompletedNode("review");
        checkpoint.setCheckpointVersion(2L);
        checkpoint.setTerminationReason("success");
        checkpoint.getNodeStatuses().put("review", "done");
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(taskStore.load(1L, "task-completed-dag")).thenReturn(Optional.of(checkpoint));
        when(repository.requeueExpiredLease(candidate, NOW, "checkpoint_resume")).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(repository).requeueExpiredLease(candidate, NOW, "checkpoint_resume");
        verify(dispatcher).dispatch("task-completed-dag");
        verify(taskFinalizer, never()).finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void runningNodeCheckpointMustNotBeAutomaticallyRequeued() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationTaskDispatcher dispatcher = mock(GenerationTaskDispatcher.class);
        service = new GenerationTaskRecoveryService(
                repository, properties(), new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService, null, null,
                taskStore, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-running-node", 1L, 9L);
        GenerationOrchestrationTask checkpoint = new GenerationOrchestrationTask();
        checkpoint.setTaskId("task-running-node");
        checkpoint.setAppId(1L);
        checkpoint.setExecutionEpoch(1L);
        checkpoint.setStatus("running");
        checkpoint.setRuntimeState(AgentRuntimeState.RUNNING);
        checkpoint.setDagFingerprint("b".repeat(64));
        checkpoint.setCurrentNode("code");
        checkpoint.getNodeStatuses().put("code", "running");
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(taskStore.load(1L, "task-running-node")).thenReturn(Optional.of(checkpoint));
        when(taskFinalizer.finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(repository, never()).requeueExpiredLease(candidate, NOW, "checkpoint_resume");
        verify(dispatcher, never()).dispatch("task-running-node");
        verify(taskFinalizer).finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void checkpointFromAnotherExecutionEpochMustNotBeRequeued() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationTaskDispatcher dispatcher = mock(GenerationTaskDispatcher.class);
        service = new GenerationTaskRecoveryService(
                repository, properties(), new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService, null, null,
                taskStore, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-stale-checkpoint", 1L, 10L);
        GenerationOrchestrationTask checkpoint = new GenerationOrchestrationTask();
        checkpoint.setTaskId("task-stale-checkpoint");
        checkpoint.setAppId(1L);
        checkpoint.setExecutionEpoch(2L);
        checkpoint.setStatus("running");
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(taskStore.load(1L, "task-stale-checkpoint")).thenReturn(Optional.of(checkpoint));
        when(taskFinalizer.finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(repository, never()).requeueExpiredLease(candidate, NOW, "checkpoint_resume");
        verify(dispatcher, never()).dispatch("task-stale-checkpoint");
        verify(taskFinalizer).finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    @Test
    void corruptedCheckpointMustFallBackToTerminalization() {
        GenerationOrchestrationTaskStore taskStore = mock(GenerationOrchestrationTaskStore.class);
        GenerationTaskDispatcher dispatcher = mock(GenerationTaskDispatcher.class);
        service = new GenerationTaskRecoveryService(
                repository, properties(), new GenerationTaskRecoveryPolicy(),
                taskFinalizer, publicationJournal, executionContextService, null, null,
                taskStore, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        GenerationTaskRecoveryCandidate candidate = orphanCandidate("task-corrupt", 1L, 7L);
        when(repository.findExpiredLeases(NOW, 25)).thenReturn(List.of(candidate));
        when(taskStore.load(1L, "task-corrupt")).thenThrow(new IllegalStateException("corrupt"));
        when(taskFinalizer.finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON)).thenReturn(true);

        assertEquals(1, service.recoverExpiredTasks());

        verify(dispatcher, never()).dispatch("task-corrupt");
        verify(taskFinalizer).finalizeExpiredLease(
                candidate, GenerationTaskStatus.FAILED, NOW,
                GenerationTaskRecoveryPolicy.ORPHAN_FAILURE_REASON);
    }

    private GenerationTaskRecoveryCandidate orphanCandidate(String taskId, Long appId, long version) {
        return new GenerationTaskRecoveryCandidate(
                taskId, appId, GenerationTaskStatus.RUNNING,
                "lost-worker", NOW.minusSeconds(1), NOW.plusSeconds(60),
                false, null, 1L, version
        );
    }

    private GenerationWorkspacePublicationJournalEntry publication(
            GenerationTaskRecoveryCandidate candidate,
            GenerationWorkspacePublicationJournalStatus status) {
        return new GenerationWorkspacePublicationJournalEntry(
                candidate.taskId(), candidate.appId(), CodeGenTypeEnum.VUE_PROJECT,
                candidate.executionEpoch(), NOW.minusSeconds(5), status, 0, 1L, "");
    }

    private GenerationTaskLeaseProperties properties() {
        GenerationTaskLeaseProperties properties = new GenerationTaskLeaseProperties();
        properties.setRecoveryBatchSize(25);
        return properties;
    }
}
