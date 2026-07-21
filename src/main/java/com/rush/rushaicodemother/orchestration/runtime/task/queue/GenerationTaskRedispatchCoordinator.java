package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatcher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** MySQL-backed transactional polling publisher for queue writes lost during Redis outages. */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.generation-task-queue", name = "transport", havingValue = "redis")
public class GenerationTaskRedispatchCoordinator {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskDispatcher dispatcher;
    private final GenerationTaskQueueProperties properties;

    public GenerationTaskRedispatchCoordinator(DurableGenerationTaskRepository repository,
                                               GenerationTaskDispatcher dispatcher,
                                               GenerationTaskQueueProperties properties) {
        this.repository = repository;
        this.dispatcher = dispatcher;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.generation-task-queue.redispatch-interval:15s}")
    public void redispatchQueuedTasks() {
        Instant now = Instant.now();
        for (String taskId : repository.findDispatchableQueuedTaskIds(
                now, now.minus(properties.getRedispatchAfter()), properties.getRedispatchBatchSize())) {
            try {
                dispatcher.dispatch(taskId);
            } catch (RuntimeException failure) {
                log.warn("Generation task redispatch failed, taskId: {}",
                        taskId, LogExceptionSanitizer.sanitize(failure));
            }
        }
    }
}
