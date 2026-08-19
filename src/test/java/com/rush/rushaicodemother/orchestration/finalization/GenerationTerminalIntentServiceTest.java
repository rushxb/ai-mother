package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
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
