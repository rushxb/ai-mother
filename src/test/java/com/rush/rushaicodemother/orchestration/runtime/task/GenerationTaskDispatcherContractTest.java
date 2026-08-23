package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.queue.DurableGenerationTaskQueue;
import com.rush.rushaicodemother.orchestration.runtime.task.queue.RedisGenerationTaskDispatcher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskDispatcherContractTest {

    @Test
    void localAdapterMustExposeDeferredDispatchOutcome() {
        GenerationTaskCommandExecutionService executionService =
                mock(GenerationTaskCommandExecutionService.class);
        when(executionService.schedule("task-local-deferred", null))
                .thenReturn(GenerationTaskDispatchResult.RETRY);
        GenerationTaskDispatcher dispatcher =
                new LocalGenerationTaskDispatcher(executionService);

        GenerationTaskDispatchResult result = dispatcher.dispatch("task-local-deferred");

        assertEquals(GenerationTaskDispatchResult.RETRY, result);
    }

    @Test
    void localAdapterMustTreatExecutorCapacityRejectionAsDeferred() {
        GenerationTaskCommandExecutionService executionService =
                mock(GenerationTaskCommandExecutionService.class);
        doThrow(new GenerationTaskCapacityExceededException("worker queue full"))
                .when(executionService).schedule("task-local-capacity", null);
        GenerationTaskDispatcher dispatcher =
                new LocalGenerationTaskDispatcher(executionService);

        GenerationTaskDispatchResult result = dispatcher.dispatch("task-local-capacity");

        assertEquals(GenerationTaskDispatchResult.RETRY, result);
    }

    @Test
    void redisAdapterMustKeepAcceptedOutcomeWhenDispatchBookkeepingFails() {
        DurableGenerationTaskQueue queue = mock(DurableGenerationTaskQueue.class);
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).recordDispatchSuccess(anyString(), any());
        GenerationTaskDispatcher dispatcher =
                new RedisGenerationTaskDispatcher(queue, repository);

        GenerationTaskDispatchResult result = dispatcher.dispatch("task-redis-accepted");

        assertEquals(GenerationTaskDispatchResult.SCHEDULED, result);
        verify(queue).enqueue("task-redis-accepted");
        verify(repository, never()).recordDispatchFailure(anyString(), anyString(), any());
    }

    @Test
    void redisAdapterMustRemainDeferredWhenFailureDiagnosticsCannotBePersisted() {
        DurableGenerationTaskQueue queue = mock(DurableGenerationTaskQueue.class);
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        doThrow(new IllegalStateException("redis unavailable"))
                .when(queue).enqueue("task-redis-deferred");
        doThrow(new IllegalStateException("database unavailable"))
                .when(repository).recordDispatchFailure(
                        anyString(), anyString(), any());
        GenerationTaskDispatcher dispatcher =
                new RedisGenerationTaskDispatcher(queue, repository);

        GenerationTaskDispatchResult result = dispatcher.dispatch("task-redis-deferred");

        assertEquals(GenerationTaskDispatchResult.RETRY, result);
    }
}
