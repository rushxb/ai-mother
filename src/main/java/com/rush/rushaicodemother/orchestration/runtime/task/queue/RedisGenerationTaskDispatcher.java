package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatcher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** Dispatches task identities to Redis while leaving MySQL queued state recoverable on outages. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.generation-task-queue", name = "transport", havingValue = "redis")
public class RedisGenerationTaskDispatcher implements GenerationTaskDispatcher {

    private final DurableGenerationTaskQueue queue;
    private final DurableGenerationTaskRepository repository;

    public RedisGenerationTaskDispatcher(DurableGenerationTaskQueue queue,
                                         DurableGenerationTaskRepository repository) {
        this.queue = queue;
        this.repository = repository;
    }

    @Override
    public void dispatch(String taskId) {
        Instant now = Instant.now();
        try {
            queue.enqueue(taskId);
            repository.recordDispatchSuccess(taskId, now);
        } catch (RuntimeException failure) {
            repository.recordDispatchFailure(taskId,
                    LogExceptionSanitizer.sanitizeMessage(failure), now);
            log.warn("Generation task queue unavailable; MySQL redispatch will retry, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(failure));
        }
    }
}
