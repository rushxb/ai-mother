package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.testing.GenerationFailureMatrix;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationProvisionalPreviewLifecycle;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag(GenerationFailureMatrix.TAG)
class GenerationTerminalEffectServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T06:00:00Z");

    @Test
    void successfulEffectMustPublishAndCleanupWithExactFence() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationEventStream eventStream = mock(GenerationEventStream.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-1", "worker-a", 7L);
        GenerationTerminalEffect effect = new GenerationTerminalEffect(
                "task-1", 11L, 22L, "heavy_generation",
                GenerationFinalizationCommand.of(
                        "task-1", 11L, fence, GenerationTaskStatus.SUCCESS,
                        null, "完成", null), 1);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(effect));
        allowOperationReceipts(repository);
        when(repository.markCompleted("task-1", 7L, "worker-test", NOW)).thenReturn(true);
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview, eventStream,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();

        verify(publisher).publishIdempotently(any(com.rush.rushaicodemother.orchestration.event.GenerationEvent.class));
        verify(eventStream).complete(
                org.mockito.ArgumentMatchers.eq("task-1"),
                org.mockito.ArgumentMatchers.argThat(event ->
                        com.rush.rushaicodemother.core.handler.GenerationStreamEvent.TASK_TERMINAL
                                .equals(event.getType())
                                && "success".equals(event.getData().get("status"))
                                && event.getData().get("deliveryReceipt")
                                instanceof java.util.Map<?, ?> receipt
                                && "heavy_generation".equals(receipt.get("actualRoute"))));
        verify(preview).stopForTerminal(11L, fence);
        verify(workspace).clear(fence, 11L, GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        verify(repository).markCompleted("task-1", 7L, "worker-test", NOW);
        verify(repository, never()).markFailed(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void eventFailureMustNotBlockPreviewAndWorkspaceCleanup() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-2", "worker-a", 8L);
        GenerationTerminalEffect effect = new GenerationTerminalEffect(
                "task-2", 12L, 23L, "agent_edit",
                GenerationFinalizationCommand.of(
                        "task-2", 12L, fence, GenerationTaskStatus.SUCCESS,
                        null, "完成", null), 2);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(effect));
        allowOperationReceipts(repository);
        doThrow(new IllegalStateException("event store unavailable"))
                .when(publisher)
                .publishIdempotently(any(com.rush.rushaicodemother.orchestration.event.GenerationEvent.class));
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();

        verify(preview).stopForTerminal(12L, fence);
        verify(workspace).clear(fence, 12L, GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        verify(repository).markFailed(
                org.mockito.ArgumentMatchers.eq("task-2"),
                org.mockito.ArgumentMatchers.eq(8L),
                org.mockito.ArgumentMatchers.eq("worker-test"),
                org.mockito.ArgumentMatchers.contains("event:"),
                org.mockito.ArgumentMatchers.eq(NOW),
                org.mockito.ArgumentMatchers.eq(NOW.plusSeconds(10)));
        verify(repository, never()).markCompleted(any(), anyLong(), any(), any());
    }

    @Test
    void persistenceFailureForOneEffectMustNotBlockTheRestOfTheClaimedBatch() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationTerminalEffect first = effect("task-first", 11L, 7L);
        GenerationTerminalEffect second = effect("task-second", 12L, 8L);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(first, second));
        allowOperationReceipts(repository);
        when(repository.markCompleted("task-first", 7L, "worker-test", NOW))
                .thenThrow(new IllegalStateException("database unavailable"));
        when(repository.markCompleted("task-second", 8L, "worker-test", NOW))
                .thenReturn(true);
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();

        verify(publisher, times(2))
                .publishIdempotently(any(com.rush.rushaicodemother.orchestration.event.GenerationEvent.class));
        verify(repository).markCompleted("task-second", 8L, "worker-test", NOW);
    }

    @Test
    void retryMustExecuteOnlyOperationsWithoutDurableReceipts() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationEventStream eventStream = mock(GenerationEventStream.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-retry", "worker-a", 9L);
        long completedMask = GenerationTerminalEffectOperation.EVENT_PUBLISH.mask()
                | GenerationTerminalEffectOperation.TASK_STREAM_COMPLETE.mask()
                | GenerationTerminalEffectOperation.PREVIEW_STOP.mask();
        GenerationTerminalEffect effect = new GenerationTerminalEffect(
                "task-retry", 13L, 24L, "heavy_generation",
                GenerationFinalizationCommand.of(
                        "task-retry", 13L, fence, GenerationTaskStatus.SUCCESS,
                        null, "完成", null), 3, completedMask);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(effect));
        when(repository.markOperationCompleted(
                "task-retry", 9L, "worker-test",
                GenerationTerminalEffectOperation.WORKSPACE_CLEAR, NOW)).thenReturn(true);
        when(repository.markCompleted("task-retry", 9L, "worker-test", NOW)).thenReturn(true);
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview, eventStream,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();

        verify(publisher, never()).publishIdempotently(any());
        verify(eventStream, never()).complete(any(), any());
        verify(preview, never()).stopForTerminal(anyLong(), any());
        verify(workspace).clear(
                fence, 13L, GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        verify(repository).markOperationCompleted(
                "task-retry", 9L, "worker-test",
                GenerationTerminalEffectOperation.WORKSPACE_CLEAR, NOW);
        verify(repository).markCompleted("task-retry", 9L, "worker-test", NOW);
    }

    @Test
    void successfulReceiptsMustSurviveASeparateOperationRetry() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationEventStream eventStream = mock(GenerationEventStream.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-partial", "worker-a", 10L);
        GenerationFinalizationCommand command = GenerationFinalizationCommand.of(
                "task-partial", 14L, fence, GenerationTaskStatus.SUCCESS,
                null, "完成", null);
        GenerationTerminalEffect firstAttempt = new GenerationTerminalEffect(
                "task-partial", 14L, 25L, "heavy_generation", command, 1, 0L);
        long retryMask = GenerationTerminalEffectOperation.TASK_STREAM_COMPLETE.mask()
                | GenerationTerminalEffectOperation.PREVIEW_STOP.mask()
                | GenerationTerminalEffectOperation.WORKSPACE_CLEAR.mask();
        GenerationTerminalEffect retry = new GenerationTerminalEffect(
                "task-partial", 14L, 25L, "heavy_generation", command, 2, retryMask);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(firstAttempt), List.of(retry));
        allowOperationReceipts(repository);
        when(repository.markCompleted("task-partial", 10L, "worker-test", NOW)).thenReturn(true);
        doThrow(new IllegalStateException("event store unavailable"))
                .doNothing()
                .when(publisher)
                .publishIdempotently(any());
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview, eventStream,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();
        service.processPending();

        verify(publisher, times(2)).publishIdempotently(any());
        verify(eventStream, times(1)).complete(any(), any());
        verify(preview, times(1)).stopForTerminal(14L, fence);
        verify(workspace, times(1)).clear(
                fence, 14L, GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        verify(repository).markCompleted("task-partial", 10L, "worker-test", NOW);
    }

    private GenerationTerminalEffect effect(String taskId, Long appId, long epoch) {
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", epoch);
        return new GenerationTerminalEffect(
                taskId, appId, 22L, "heavy_generation",
                GenerationFinalizationCommand.of(
                        taskId, appId, fence, GenerationTaskStatus.SUCCESS,
                        null, "完成", null), 1);
    }

    private void allowOperationReceipts(GenerationTerminalEffectRepository repository) {
        when(repository.markOperationCompleted(any(), anyLong(), any(), any(), any()))
                .thenReturn(true);
    }
}
