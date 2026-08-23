package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatchResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatcher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 基于 MySQL 真相源重新分派长期停留在 QUEUED 的任务。
 *
 * <p>local 与 Redis adapter 共用这一恢复环，避免瞬时容量或传输故障改变任务终态。</p>
 */
@Slf4j
@Component
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

    /** 处理{@code redispatch}{@code Queued}任务。 */
    @Scheduled(fixedDelayString = "${app.generation-task-queue.redispatch-interval:15s}")
    public void redispatchQueuedTasks() {
        Instant now = Instant.now();
        for (String taskId : repository.findDispatchableQueuedTaskIds(
                now, now.minus(properties.getRedispatchAfter()), properties.getRedispatchBatchSize())) {
            try {
                GenerationTaskDispatchResult result = dispatcher.dispatch(taskId);
                if (result == GenerationTaskDispatchResult.RETRY) {
                    log.debug("Generation task remains queued for a later redispatch, taskId: {}", taskId);
                }
            } catch (RuntimeException failure) {
                log.warn("Generation task redispatch failed, taskId: {}",
                        taskId, LogExceptionSanitizer.sanitize(failure));
            }
        }
    }
}
