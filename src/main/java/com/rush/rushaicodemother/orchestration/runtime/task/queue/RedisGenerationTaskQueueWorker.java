package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskCommandExecutionService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatchResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Redis 长驻消费者；任务本轮活动执行期间，队列消息保持待确认并持续续期。 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.generation-task-queue", name = "transport", havingValue = "redis")
public class RedisGenerationTaskQueueWorker implements SmartLifecycle {

    private final DurableGenerationTaskQueue queue;
    private final GenerationTaskCommandExecutionService executionService;
    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationTaskQueueProperties properties;
    private final Map<String, GenerationTaskQueueDelivery> activeDeliveries = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Thread consumerThread;
    private volatile Thread heartbeatThread;

    public RedisGenerationTaskQueueWorker(DurableGenerationTaskQueue queue,
                                          GenerationTaskCommandExecutionService executionService,
                                          DurableGenerationTaskRepository repository,
                                          GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
                                          GenerationTaskQueueProperties properties) {
        this.queue = queue;
        this.executionService = executionService;
        this.repository = repository;
        this.runtimeLifecycleService = runtimeLifecycleService;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        consumerThread = Thread.ofVirtual()
                .name("generation-redis-queue-consumer")
                .start(this::consumeLoop);
        heartbeatThread = Thread.ofVirtual()
                .name("generation-redis-queue-heartbeat")
                .start(this::heartbeatLoop);
    }

    @Override
    public void stop() {
        running.set(false);
        Thread current = consumerThread;
        if (current != null) {
            current.interrupt();
        }
        Thread currentHeartbeat = heartbeatThread;
        if (currentHeartbeat != null) {
            currentHeartbeat.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private void consumeLoop() {
        while (running.get()) {
            try {
                process(queue.reclaimExpired());
                process(queue.readNew());
            } catch (RuntimeException failure) {
                if (running.get()) {
                    log.error("生成任务 Redis 队列消费轮次失败",
                            LogExceptionSanitizer.sanitize(failure));
                    pauseAfterFailure();
                }
            }
        }
    }

    private void heartbeatLoop() {
        while (running.get()) {
            try {
                Thread.sleep(properties.getDeliveryHeartbeatInterval());
                if (running.get()) {
                    heartbeatActiveDeliveries();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException failure) {
                if (running.get()) {
                    log.error("生成任务 Redis 队列投递续期失败",
                            LogExceptionSanitizer.sanitize(failure));
                }
            }
        }
    }

    private void process(List<GenerationTaskQueueDelivery> deliveries) {
        if (deliveries == null) {
            return;
        }
        for (GenerationTaskQueueDelivery delivery : deliveries) {
            process(delivery);
        }
    }

    void process(GenerationTaskQueueDelivery delivery) {
        AtomicBoolean completedBeforeRegistration = new AtomicBoolean(false);
        try {
            GenerationTaskDispatchResult result = executionService.schedule(delivery.taskId(), () -> {
                acknowledgeSafely(delivery);
                if (activeDeliveries.remove(delivery.messageId()) == null) {
                    completedBeforeRegistration.set(true);
                }
            });
            switch (result) {
                case SCHEDULED -> {
                    activeDeliveries.put(delivery.messageId(), delivery);
                    if (completedBeforeRegistration.get()) {
                        activeDeliveries.remove(delivery.messageId());
                    }
                }
                case ALREADY_ACTIVE, TERMINAL -> queue.acknowledge(delivery);
                case RETRY -> handleRetry(delivery, "task_reservation_unavailable");
            }
        } catch (RuntimeException failure) {
            handleRetry(delivery, LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private void handleRetry(GenerationTaskQueueDelivery delivery, String reason) {
        if (delivery.deliveryCount() < properties.getMaxDeliveryAttempts()) {
            return;
        }
        try {
            repository.findByTaskId(delivery.taskId()).ifPresent(task -> {
                if (!task.terminal()) {
                    runtimeLifecycleService.completeUnowned(
                            delivery.taskId(), GenerationTaskStatus.FAILED, "queue_delivery_exhausted");
                }
            });
        } finally {
            queue.deadLetter(delivery, reason);
        }
    }

    private void heartbeatActiveDeliveries() {
        if (activeDeliveries.isEmpty()) {
            return;
        }
        queue.heartbeat(List.copyOf(activeDeliveries.values()));
    }

    private void acknowledgeSafely(GenerationTaskQueueDelivery delivery) {
        try {
            queue.acknowledge(delivery);
        } catch (RuntimeException failure) {
            log.warn("生成任务队列确认失败，终态任务将在重放时再次确认，taskId: {}",
                    delivery.taskId(), LogExceptionSanitizer.sanitize(failure));
        }
    }

    private void pauseAfterFailure() {
        try {
            Thread.sleep(Math.min(1000L, properties.getPollTimeout().toMillis()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
