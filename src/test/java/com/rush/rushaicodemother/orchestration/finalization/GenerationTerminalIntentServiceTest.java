package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTerminalIntentServiceTest {

    @Test
    void preparedIntentFromAnotherLeaseOwnerMustBeRejected() {
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        GenerationTerminalIntentService service = new GenerationTerminalIntentService(repository);
        GenerationFinalizationCommand expected = command("current-worker");
        when(repository.findFinalizationIntent("task-1", 3L))
                .thenReturn(Optional.of(command("stale-worker")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.requirePrepared(expected));

        assertEquals("已发布任务终态意图与当前执行上下文不一致", failure.getMessage());
    }

    @Test
    void abortPreparedMustUseExactCommandAndCurrentTime() {
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        Instant now = Instant.parse("2026-08-23T05:00:00Z");
        GenerationTerminalIntentService service = new GenerationTerminalIntentService(
                repository, Clock.fixed(now, ZoneOffset.UTC));
        GenerationFinalizationCommand command = command("current-worker");
        when(repository.abortFinalizationIntent(command, now)).thenReturn(true);

        assertEquals(true, service.abortPrepared(command));

        verify(repository).abortFinalizationIntent(command, now);
    }

    private GenerationFinalizationCommand command(String leaseOwner) {
        return GenerationFinalizationCommand.of(
                "task-1",
                1L,
                new GenerationExecutionFence("task-1", leaseOwner, 3L),
                GenerationTaskStatus.SUCCESS,
                null,
                "冻结的完整终态",
                null);
    }
}
