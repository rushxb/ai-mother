package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatchResult;
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
    public GenerationTaskDispatchResult dispatch(String taskId) {
        Instant now = Instant.now();
        try {
            queue.enqueue(taskId);
        } catch (RuntimeException failure) {
            recordDispatchFailureBestEffort(taskId, failure, now);
            log.warn("Generation task queue unavailable; MySQL redispatch will retry, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(failure));
            return GenerationTaskDispatchResult.RETRY;
        }
        recordDispatchSuccessBestEffort(taskId, now);
        return GenerationTaskDispatchResult.SCHEDULED;
    }

    /** 队列接纳成功是主事实；诊断字段写入失败不能把它改判为未分派。 */
    private void recordDispatchSuccessBestEffort(String taskId, Instant dispatchedAt) {
        try {
            repository.recordDispatchSuccess(taskId, dispatchedAt);
        } catch (RuntimeException diagnosticFailure) {
            log.warn("Generation task dispatch success bookkeeping failed, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(diagnosticFailure));
        }
    }

    /** 分发诊断是辅助信息，写入失败不能覆盖“任务仍在持久队列中”的主事实。 */
    private void recordDispatchFailureBestEffort(String taskId,
                                                 RuntimeException dispatchFailure,
                                                 Instant failedAt) {
        try {
            repository.recordDispatchFailure(
                    taskId, LogExceptionSanitizer.sanitizeMessage(dispatchFailure), failedAt);
        } catch (RuntimeException diagnosticFailure) {
            log.warn("Generation task dispatch diagnostic persistence failed, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(diagnosticFailure));
        }
    }
}
