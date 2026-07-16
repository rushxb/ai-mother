package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded in-process task executor backed by one virtual thread per admitted task.
 *
 * <p>The admission semaphore bounds running plus queued work, while the concurrency semaphore
 * bounds expensive model/build execution. This preserves virtual-thread isolation without pooling
 * virtual threads. The {@link GenerationTaskExecutor} seam can later be replaced by a durable
 * queue without changing submission or pipeline code.</p>
 */
@Component
public class VirtualThreadGenerationTaskExecutor implements GenerationTaskExecutor {

    private final ExecutorService executor;
    private final Semaphore admissionPermits;
    private final Semaphore concurrencyPermits;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final Duration shutdownTimeout;

    public VirtualThreadGenerationTaskExecutor(GenerationTaskExecutorProperties properties) {
        Objects.requireNonNull(properties, "properties");
        int maxConcurrency = requirePositive(properties.getMaxConcurrency(), "maxConcurrency");
        int queueCapacity = requirePositive(properties.getQueueCapacity(), "queueCapacity");
        this.shutdownTimeout = requirePositive(properties.getShutdownTimeout(), "shutdownTimeout");
        this.admissionPermits = new Semaphore(maxConcurrency + queueCapacity, true);
        this.concurrencyPermits = new Semaphore(maxConcurrency, true);

        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("generation-task-worker-", 0)
                .factory();
        this.executor = Executors.newThreadPerTaskExecutor(threadFactory);
    }

    @Override
    public void execute(String taskId, Runnable task) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        Objects.requireNonNull(task, "task");
        if (shuttingDown.get() || !admissionPermits.tryAcquire()) {
            throw capacityExceeded(taskId);
        }

        queuedTasks.incrementAndGet();
        try {
            executor.execute(() -> runAdmitted(taskId, task));
        } catch (RejectedExecutionException rejection) {
            queuedTasks.decrementAndGet();
            admissionPermits.release();
            throw capacityExceeded(taskId);
        }
    }

    private void runAdmitted(String taskId, Runnable task) {
        boolean removedFromQueue = false;
        boolean running = false;
        try {
            concurrencyPermits.acquire();
            queuedTasks.decrementAndGet();
            removedFromQueue = true;
            activeTasks.incrementAndGet();
            running = true;
            runNamed(taskId, task);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (!removedFromQueue) {
                queuedTasks.decrementAndGet();
            }
            if (running) {
                activeTasks.decrementAndGet();
                concurrencyPermits.release();
            }
            admissionPermits.release();
        }
    }

    private void runNamed(String taskId, Runnable task) {
        Thread currentThread = Thread.currentThread();
        String workerName = currentThread.getName();
        currentThread.setName("generation-task-" + taskId);
        try {
            task.run();
        } finally {
            currentThread.setName(workerName);
        }
    }

    @PreDestroy
    void shutdown() {
        shuttingDown.set(true);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    int activeTaskCount() {
        return activeTasks.get();
    }

    int queuedTaskCount() {
        return queuedTasks.get();
    }

    private GenerationTaskCapacityExceededException capacityExceeded(String taskId) {
        return new GenerationTaskCapacityExceededException(
                "Generation task executor rejected task " + taskId
                        + "; active=" + activeTasks.get()
                        + ", queued=" + queuedTasks.get());
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String fieldName) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }
}
