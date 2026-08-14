package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.mapper.GenerationTerminalEffectMapper;
import com.rush.rushaicodemother.mapper.projection.GenerationTerminalEffectBacklogRow;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectBacklog;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectAdminItem;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffect;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectOperation;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisGenerationTerminalEffectRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Test
    void claimMustExposeDurableOperationReceipts() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-retry", 11L,
                new GenerationExecutionFence("task-retry", "worker-a", 7L),
                GenerationTaskStatus.SUCCESS, null, "完成", null);
        GenerationTask candidate = GenerationTask.builder()
                .taskId("task-retry")
                .appId(11L)
                .userId(22L)
                .route("heavy_generation")
                .terminalIntentSchemaVersion(1)
                .terminalIntentPayloadJson(GenerationFinalizationCommandCodec.toJson(command))
                .terminalIntentExecutionEpoch(7L)
                .terminalEffectsAttempts(2)
                .terminalEffectsCompletedMask(
                        GenerationTerminalEffectOperation.EVENT_PUBLISH.mask())
                .build();
        LocalDateTime databaseNow = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        LocalDateTime leaseUntil = LocalDateTime.ofInstant(
                NOW.plusSeconds(30), ZoneId.systemDefault());
        when(mapper.selectPending(databaseNow, 100, 10)).thenReturn(List.of(candidate));
        when(mapper.claim(
                "task-retry", 7L, 2, 10, "worker-test", databaseNow, leaseUntil))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        GenerationTerminalEffect effect = repository.claimBatch(
                NOW, NOW.plusSeconds(30), "worker-test", 100, 10).getFirst();

        assertFalse(effect.pending(GenerationTerminalEffectOperation.EVENT_PUBLISH));
        assertTrue(effect.pending(GenerationTerminalEffectOperation.WORKSPACE_CLEAR));
        assertEquals(3, effect.attempts());
    }

    @Test
    void operationReceiptMustBeFencedByLeaseAndExecutionEpoch() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        LocalDateTime completedAt = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        when(mapper.markOperationCompleted(
                "task-1", 9L, "worker-test",
                GenerationTerminalEffectOperation.PREVIEW_STOP.mask(), completedAt))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertTrue(repository.markOperationCompleted(
                "task-1", 9L, "worker-test",
                GenerationTerminalEffectOperation.PREVIEW_STOP, NOW));

        verify(mapper).markOperationCompleted(
                "task-1", 9L, "worker-test",
                GenerationTerminalEffectOperation.PREVIEW_STOP.mask(), completedAt);
    }

    @Test
    void aggregateCompletionMustRequireEveryKnownOperationReceipt() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        LocalDateTime completedAt = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        when(mapper.markCompleted(
                "task-1", 9L, "worker-test",
                GenerationTerminalEffectOperation.requiredMask(), completedAt))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertTrue(repository.markCompleted("task-1", 9L, "worker-test", NOW));

        verify(mapper).markCompleted(
                "task-1", 9L, "worker-test",
                GenerationTerminalEffectOperation.requiredMask(), completedAt);
    }

    @Test
    void malformedIntentMustBeQuarantinedWithoutBlockingTheBatch() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        GenerationTask malformed = GenerationTask.builder()
                .taskId("task-corrupt")
                .appId(11L)
                .userId(22L)
                .route("heavy_generation")
                .terminalIntentSchemaVersion(1)
                .terminalIntentPayloadJson("{")
                .terminalIntentExecutionEpoch(7L)
                .terminalEffectsAttempts(0)
                .build();
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        LocalDateTime databaseNow = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        when(mapper.selectPending(databaseNow, 100, 10)).thenReturn(List.of(malformed));
        when(mapper.markMalformed(
                org.mockito.ArgumentMatchers.eq("task-corrupt"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10),
                anyString(),
                org.mockito.ArgumentMatchers.eq(databaseNow)))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertTrue(repository.claimBatch(
                now, now.plusSeconds(30), "worker-test", 100, 10).isEmpty());

        verify(mapper).markMalformed(
                org.mockito.ArgumentMatchers.eq("task-corrupt"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.contains("无法解码"),
                org.mockito.ArgumentMatchers.eq(databaseNow));
        verify(mapper, never()).claim(
                anyString(), anyLong(), anyInt(), anyInt(), anyString(), any(), any());
    }

    @Test
    void intentIdentityMismatchMustBeQuarantinedBeforeClaim() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        GenerationFinalizationCommand mismatched = GenerationFinalizationCommand.of(
                "another-task", 99L,
                new GenerationExecutionFence("another-task", "worker-a", 7L),
                GenerationTaskStatus.SUCCESS, null, "完成", null);
        GenerationTask candidate = GenerationTask.builder()
                .taskId("task-real")
                .appId(11L)
                .userId(22L)
                .route("heavy_generation")
                .terminalIntentSchemaVersion(1)
                .terminalIntentPayloadJson(GenerationFinalizationCommandCodec.toJson(mismatched))
                .terminalIntentExecutionEpoch(7L)
                .terminalEffectsAttempts(0)
                .build();
        Instant now = Instant.parse("2026-08-13T08:00:00Z");
        LocalDateTime databaseNow = LocalDateTime.ofInstant(now, ZoneId.systemDefault());
        when(mapper.selectPending(databaseNow, 100, 10)).thenReturn(List.of(candidate));
        when(mapper.markMalformed(
                org.mockito.ArgumentMatchers.eq("task-real"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10),
                anyString(),
                org.mockito.ArgumentMatchers.eq(databaseNow)))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertTrue(repository.claimBatch(
                now, now.plusSeconds(30), "worker-test", 100, 10).isEmpty());

        verify(mapper).markMalformed(
                org.mockito.ArgumentMatchers.eq("task-real"),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(0),
                org.mockito.ArgumentMatchers.eq(10),
                org.mockito.ArgumentMatchers.contains("身份不一致"),
                org.mockito.ArgumentMatchers.eq(databaseNow));
        verify(mapper, never()).claim(
                anyString(), anyLong(), anyInt(), anyInt(), anyString(), any(), any());
    }

    @Test
    void backlogProjectionMustPreserveAllOperationalStates() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        LocalDateTime databaseNow = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        LocalDateTime oldest = databaseNow.minusMinutes(5);
        when(mapper.inspectBacklog(databaseNow, 10)).thenReturn(
                new GenerationTerminalEffectBacklogRow(8L, 3L, 2L, 1L, oldest));
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        GenerationTerminalEffectBacklog backlog = repository.inspectBacklog(NOW, 10);

        assertEquals(8L, backlog.pending());
        assertEquals(3L, backlog.retrying());
        assertEquals(2L, backlog.leased());
        assertEquals(1L, backlog.deadLetter());
        assertEquals(NOW.minusSeconds(300), backlog.oldestPendingAt());
    }

    @Test
    void replayMustResetExactDeadLetterAndAppendAuditInOneRepositoryTransaction() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        LocalDateTime requestedAt = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        when(mapper.selectReplayAttemptsForUpdate(
                "task-1", 9L, 10, requestedAt)).thenReturn(12);
        when(mapper.replayDeadLetter("task-1", 9L, 12, requestedAt)).thenReturn(1);
        when(mapper.insertReplayAudit(
                anyString(), org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(12),
                org.mockito.ArgumentMatchers.eq(88L), org.mockito.ArgumentMatchers.eq(requestedAt)))
                .thenReturn(1);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertTrue(repository.replayDeadLetter("task-1", 9L, 88L, NOW, 10));

        verify(mapper).replayDeadLetter("task-1", 9L, 12, requestedAt);
        verify(mapper).insertReplayAudit(
                anyString(), org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.eq(9L), org.mockito.ArgumentMatchers.eq(12),
                org.mockito.ArgumentMatchers.eq(88L), org.mockito.ArgumentMatchers.eq(requestedAt));
    }

    @Test
    void replayMustNotMutateWhenExactDeadLetterIsNotEligible() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        LocalDateTime requestedAt = LocalDateTime.ofInstant(NOW, ZoneId.systemDefault());
        when(mapper.selectReplayAttemptsForUpdate(
                "task-1", 9L, 10, requestedAt)).thenReturn(null);
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        assertFalse(repository.replayDeadLetter("task-1", 9L, 88L, NOW, 10));

        verify(mapper, never()).replayDeadLetter(
                anyString(), anyLong(), anyInt(), any());
        verify(mapper, never()).insertReplayAudit(
                anyString(), anyString(), anyLong(), anyInt(), anyLong(), any());
    }

    @Test
    void exhaustedAttemptWithActiveLeaseMustRemainLeasedUntilWorkerFinishes() {
        GenerationTerminalEffectMapper mapper = mock(GenerationTerminalEffectMapper.class);
        GenerationTask activeLastAttempt = GenerationTask.builder()
                .taskId("task-active")
                .appId(11L)
                .route("heavy_generation")
                .terminalIntentExecutionEpoch(9L)
                .terminalEffectsAttempts(10)
                .terminalEffectsLeaseOwner("worker-a")
                .terminalEffectsLeaseUntil(LocalDateTime.ofInstant(
                        NOW.plusSeconds(30), ZoneId.systemDefault()))
                .terminalIntentFinalizedAt(LocalDateTime.ofInstant(
                        NOW.minusSeconds(60), ZoneId.systemDefault()))
                .build();
        when(mapper.selectOutstanding(
                LocalDateTime.ofInstant(NOW, ZoneId.systemDefault()), 10, 100))
                .thenReturn(List.of(activeLastAttempt));
        MyBatisGenerationTerminalEffectRepository repository =
                new MyBatisGenerationTerminalEffectRepository(mapper);

        List<GenerationTerminalEffectAdminItem> items =
                repository.listOutstanding(NOW, 10, 100);

        assertEquals(1, items.size());
        assertEquals("leased", items.getFirst().state());
    }
}
