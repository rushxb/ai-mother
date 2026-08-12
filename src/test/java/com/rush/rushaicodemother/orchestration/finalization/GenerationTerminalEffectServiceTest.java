package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationProvisionalPreviewLifecycle;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTerminalEffectServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-12T06:00:00Z");

    @Test
    void successfulEffectMustPublishAndCleanupWithExactFence() {
        GenerationTerminalEffectRepository repository = mock(GenerationTerminalEffectRepository.class);
        GenerationEventPublisher publisher = mock(GenerationEventPublisher.class);
        GenerationExecutionWorkspaceService workspace = mock(GenerationExecutionWorkspaceService.class);
        GenerationProvisionalPreviewLifecycle preview = mock(GenerationProvisionalPreviewLifecycle.class);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-1", "worker-a", 7L);
        GenerationTerminalEffect effect = new GenerationTerminalEffect(
                "task-1", 11L, 22L, "heavy_generation",
                GenerationFinalizationCommand.of(
                        "task-1", 11L, fence, GenerationTaskStatus.SUCCESS,
                        null, "完成", null), 1);
        when(repository.claimBatch(any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(effect));
        when(repository.markCompleted("task-1", 7L, "worker-test", NOW)).thenReturn(true);
        GenerationTerminalEffectService service = new GenerationTerminalEffectService(
                repository, publisher, workspace, preview,
                Clock.fixed(NOW, ZoneOffset.UTC), "worker-test");

        service.processPending();

        verify(publisher).publishIdempotently(any(com.rush.rushaicodemother.orchestration.event.GenerationEvent.class));
        verify(preview).stopForTerminal(11L, fence);
        verify(workspace).clear(fence, 11L, GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        verify(repository).markCompleted("task-1", 7L, "worker-test", NOW);
        verify(repository, never()).markFailed(any(), anyLong(), any(), any(), any(), any());
    }
}
