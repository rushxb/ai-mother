package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatcher;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/** 将任务标识分派到 Redis，同时使 MySQL 排队状态在中断时可恢复。 */
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

    /**
 * 分发 Redis 生成任务{@code Dispatcher}。
 *
 * @param taskId 任务编号
 */
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
