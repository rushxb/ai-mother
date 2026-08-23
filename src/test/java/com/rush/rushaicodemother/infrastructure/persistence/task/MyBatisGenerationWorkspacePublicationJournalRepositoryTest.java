package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationWorkspacePublicationJournalMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalEntry;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationJournalStatus;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspacePublicationPointer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationWorkspacePublicationJournalRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-20T09:00:00Z");
    private static final Long APP_ID = 11L;
    private static final CodeGenTypeEnum CODE_GEN_TYPE = CodeGenTypeEnum.VUE_PROJECT;

    @Test
    void concurrentPrepareMustReuseThePersistedPublicationTimestamp() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer candidate = pointer(
                "task-1", 5L, NOW.plusSeconds(30));
        GenerationWorkspacePublicationPointer persistedPointer = pointer("task-1", 5L, NOW);
        when(mapper.selectOne("task-1"))
                .thenReturn(null, task(persistedPointer,
                        GenerationWorkspacePublicationJournalStatus.PREPARED, 0, 1L, null));
        when(mapper.prepareNew(
                eq("task-1"), eq(APP_ID), eq(CODE_GEN_TYPE.getValue()), eq(5L),
                eq(local(candidate.publishedAt())), eq(local(NOW.plusSeconds(60)))))
                .thenReturn(0);

        GenerationWorkspacePublicationJournalEntry prepared = repository.prepare(
                candidate, NOW.plusSeconds(60));

        assertEquals(persistedPointer, prepared.pointer());
        verify(mapper).prepareNew(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(candidate.publishedAt()), local(NOW.plusSeconds(60)));
    }

    @Test
    void prepareMustRejectAConflictingExecutionIdentity() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        when(mapper.selectOne("task-1")).thenReturn(task(
                pointer("task-1", 4L, NOW),
                GenerationWorkspacePublicationJournalStatus.PREPARED, 0, 1L, null));

        assertThrows(IllegalStateException.class, () -> repository.prepare(
                pointer("task-1", 5L, NOW.plusSeconds(1)), NOW.plusSeconds(1)));

        verify(mapper, never()).reopen(
                eq("task-1"), eq(APP_ID), eq(CODE_GEN_TYPE.getValue()),
                eq(4L), eq(local(NOW)), eq(local(NOW.plusSeconds(1))));
    }

    @Test
    void rolledBackPrepareMustFailClosedWhenReopenDidNotTakeEffect() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 5L, NOW);
        GenerationTask rolledBack = task(pointer,
                GenerationWorkspacePublicationJournalStatus.ROLLED_BACK, 1, 3L, "failed");
        when(mapper.selectOne("task-1")).thenReturn(rolledBack, rolledBack);
        when(mapper.reopen(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), local(NOW.plusSeconds(1))))
                .thenReturn(0);

        assertThrows(IllegalStateException.class,
                () -> repository.prepare(pointer, NOW.plusSeconds(1)));
    }

    @Test
    void claimPendingMustReturnOnlyRowsWonByOptimisticVersion() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer firstPointer = pointer("task-1", 5L, NOW);
        GenerationWorkspacePublicationPointer secondPointer = pointer(
                "task-2", 6L, NOW.plusSeconds(1));
        GenerationTask first = task(firstPointer,
                GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED,
                2, 7L, "old");
        GenerationTask second = task(secondPointer,
                GenerationWorkspacePublicationJournalStatus.PREPARED,
                3, 9L, null);
        when(mapper.selectPending(local(NOW), 2, 5)).thenReturn(List.of(first, second));
        when(mapper.claim("task-1", 7L, 5, local(NOW), local(NOW.plusSeconds(30))))
                .thenReturn(1);
        when(mapper.claim("task-2", 9L, 5, local(NOW), local(NOW.plusSeconds(30))))
                .thenReturn(0);

        List<GenerationWorkspacePublicationJournalEntry> claimed = repository.claimPending(
                NOW, 2, 5, Duration.ofSeconds(30));

        assertEquals(1, claimed.size());
        assertEquals(firstPointer, claimed.getFirst().pointer());
        assertEquals(3, claimed.getFirst().attempts());
        assertEquals(8L, claimed.getFirst().version());
        assertEquals("", claimed.getFirst().lastError());
    }

    @Test
    void exhaustedCriticalJournalMustStillFlowThroughTheOptimisticClaim() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-critical", 5L, NOW);
        GenerationTask exhausted = task(
                pointer,
                GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED,
                20,
                17L,
                "metadata unavailable");
        when(mapper.selectPending(local(NOW), 1, 20)).thenReturn(List.of(exhausted));
        when(mapper.claim(
                "task-critical", 17L, 20, local(NOW), local(NOW.plusSeconds(30))))
                .thenReturn(1);

        List<GenerationWorkspacePublicationJournalEntry> claimed = repository.claimPending(
                NOW, 1, 20, Duration.ofSeconds(30));

        assertEquals(1, claimed.size());
        assertEquals(GenerationWorkspacePublicationJournalStatus.FILESYSTEM_ACTIVATED,
                claimed.getFirst().status());
        assertEquals(21, claimed.getFirst().attempts());
        assertEquals(18L, claimed.getFirst().version());
    }

    @Test
    void zeroRowTransitionMustAcceptOnlyTheExactPersistedTerminalState() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 5L, NOW);
        when(mapper.markCommitted(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), local(NOW.plusSeconds(1))))
                .thenReturn(0);
        when(mapper.selectOne("task-1")).thenReturn(task(
                pointer,
                GenerationWorkspacePublicationJournalStatus.COMMITTED,
                1, 4L, null));

        repository.markCommitted(pointer, NOW.plusSeconds(1));

        GenerationWorkspacePublicationPointer mismatchedTimestamp = pointer(
                "task-1", 5L, NOW.plusSeconds(2));
        when(mapper.markCommitted(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(mismatchedTimestamp.publishedAt()), local(NOW.plusSeconds(3))))
                .thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> repository.markCommitted(mismatchedTimestamp, NOW.plusSeconds(3)));
    }

    @Test
    void reconciliationFailureMustBeBoundedAndFailClosedWhenPendingRowWasNotUpdated() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 5L, NOW);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        when(mapper.recordFailure(
                eq("task-1"), eq(APP_ID), eq(CODE_GEN_TYPE.getValue()), eq(5L),
                eq(local(NOW)), error.capture(), eq(local(NOW.plusSeconds(1)))))
                .thenReturn(1);

        repository.recordReconciliationFailure(
                pointer, "x".repeat(2_000), NOW.plusSeconds(1));

        assertEquals(1_024, error.getValue().length());

        when(mapper.recordFailure(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), "retry", local(NOW.plusSeconds(2))))
                .thenReturn(0);
        when(mapper.selectOne("task-1")).thenReturn(task(
                pointer,
                GenerationWorkspacePublicationJournalStatus.PREPARED,
                2, 5L, "old"));
        assertThrows(IllegalStateException.class, () -> repository.recordReconciliationFailure(
                pointer, "retry", NOW.plusSeconds(2)));
    }

    @Test
    void reconciliationFailureMayRaceWithAnExactTerminalTransition() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 5L, NOW);
        when(mapper.recordFailure(
                "task-1", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), "late", local(NOW.plusSeconds(1))))
                .thenReturn(0);
        when(mapper.selectOne("task-1")).thenReturn(task(
                pointer,
                GenerationWorkspacePublicationJournalStatus.COMMITTED,
                2, 6L, null));

        repository.recordReconciliationFailure(pointer, "late", NOW.plusSeconds(1));
    }

    @Test
    void preparedRollbackMustUseTheAtomicOwningExecutionExpiryTransition() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);
        GenerationWorkspacePublicationPointer pointer = pointer("task-expired", 5L, NOW);
        when(mapper.rollbackPreparedIfOwningExecutionExpired(
                "task-expired", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), "owner expired", local(NOW.plusSeconds(1))))
                .thenReturn(1);

        assertTrue(repository.rollbackPreparedIfOwningExecutionExpired(
                pointer, "owner expired", NOW.plusSeconds(1)));

        verify(mapper).rollbackPreparedIfOwningExecutionExpired(
                "task-expired", APP_ID, CODE_GEN_TYPE.getValue(), 5L,
                local(NOW), "owner expired", local(NOW.plusSeconds(1)));
    }

    @Test
    void transitionValidationMustRejectNullPointerBeforeMapperDereference() {
        GenerationWorkspacePublicationJournalMapper mapper =
                mock(GenerationWorkspacePublicationJournalMapper.class);
        MyBatisGenerationWorkspacePublicationJournalRepository repository =
                new MyBatisGenerationWorkspacePublicationJournalRepository(mapper);

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> repository.markFilesystemActivated(null, NOW));

        assertTrue(failure.getMessage().contains("pointer"));
    }

    private GenerationTask task(GenerationWorkspacePublicationPointer pointer,
                                GenerationWorkspacePublicationJournalStatus status,
                                int attempts,
                                long version,
                                String error) {
        GenerationTask task = new GenerationTask();
        task.setTaskId(pointer.taskId());
        task.setAppId(pointer.appId());
        task.setExecutionEpoch(pointer.executionEpoch());
        task.setPublicationStatus(status.value());
        task.setPublicationCodeGenType(pointer.codeGenType().getValue());
        task.setPublicationExecutionEpoch(pointer.executionEpoch());
        task.setPublicationPublishedAt(local(pointer.publishedAt()));
        task.setPublicationAttempts(attempts);
        task.setPublicationVersion(version);
        task.setPublicationError(error);
        return task;
    }

    private GenerationWorkspacePublicationPointer pointer(String taskId,
                                                           long executionEpoch,
                                                           Instant publishedAt) {
        return new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                APP_ID,
                CODE_GEN_TYPE,
                taskId,
                executionEpoch,
                publishedAt
        );
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }
}
