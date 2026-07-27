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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每个承认的任务由一个虚拟线程支持的有界进程内任务执行器。
 *
 * <p> 准入信号量边界运行加上排队工作，而并发信号量
 * 限制昂贵的模型/构建执行。这可以保留虚拟线程隔离而无需池化
 * 虚拟线程。 {@link GenerationTaskExecutor} 接缝稍后可以更换为耐用的接缝
 * 队列无需更改提交或管道代码。</p>
 */
@Component
public class VirtualThreadGenerationTaskExecutor implements GenerationTaskExecutor {

    private final ExecutorService executor;
    private final Semaphore admissionPermits;
    private final Semaphore concurrencyPermits;
    private final GenerationTaskWatchdog watchdog;
    private final ConcurrentMap<String, TaskControl> taskControls = new ConcurrentHashMap<>();
    private final AtomicInteger activeTasks = new AtomicInteger();
    private final AtomicInteger queuedTasks = new AtomicInteger();
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final Duration shutdownTimeout;
    private final Duration queuePolicyCheckInterval;

    public VirtualThreadGenerationTaskExecutor(GenerationTaskExecutorProperties properties,
                                               GenerationTaskWatchdog watchdog) {
        Objects.requireNonNull(properties, "properties");
        this.watchdog = Objects.requireNonNull(watchdog, "watchdog");
        int maxConcurrency = requirePositive(properties.getMaxConcurrency(), "maxConcurrency");
        int queueCapacity = requirePositive(properties.getQueueCapacity(), "queueCapacity");
        this.shutdownTimeout = requirePositive(properties.getShutdownTimeout(), "shutdownTimeout");
        this.queuePolicyCheckInterval = requirePositive(
                properties.getQueuePolicyCheckInterval(), "queuePolicyCheckInterval");
        this.admissionPermits = new Semaphore(maxConcurrency + queueCapacity, true);
        this.concurrencyPermits = new Semaphore(maxConcurrency, true);

        ThreadFactory threadFactory = Thread.ofVirtual()
                .name("generation-task-worker-", 0)
                .factory();
        this.executor = Executors.newThreadPerTaskExecutor(threadFactory);
    }

    @Override
    public void execute(String taskId, Runnable task) {
        executeInternal(taskId, null, task);
    }

    @Override
    public void execute(GenerationTaskExecution execution, Runnable task) {
        Objects.requireNonNull(execution, "execution");
        executeInternal(execution.taskId(), execution, task);
    }

    private void executeInternal(String taskId, GenerationTaskExecution execution, Runnable task) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId cannot be blank");
        }
        Objects.requireNonNull(task, "task");
        if (shuttingDown.get() || !admissionPermits.tryAcquire()) {
            throw capacityExceeded(taskId);
        }

        TaskControl control = new TaskControl(taskId);
        if (taskControls.putIfAbsent(taskId, control) != null) {
            admissionPermits.release();
            throw new IllegalStateException("generation task is already admitted: " + taskId);
        }
        queuedTasks.incrementAndGet();
        try {
            if (execution != null) {
                control.bindWatchdog(watchdog.watch(execution, control::interruptIfRunning));
            }
            executor.execute(() -> runAdmitted(taskId, execution, task, control));
        } catch (RejectedExecutionException rejection) {
            releaseRejected(control);
            throw capacityExceeded(taskId);
        } catch (RuntimeException submissionFailure) {
            releaseRejected(control);
            throw submissionFailure;
        }
    }

    private void runAdmitted(String taskId,
                             GenerationTaskExecution execution,
                             Runnable task,
                             TaskControl control) {
        boolean removedFromQueue = false;
        boolean running = false;
        control.bindWorker(Thread.currentThread());
        try {
            boolean permitAcquired = awaitConcurrencyPermit(execution);
            queuedTasks.decrementAndGet();
            removedFromQueue = true;
            if (permitAcquired) {
                activeTasks.incrementAndGet();
                running = true;
                control.markRunning();
            }
            // 过期和取消的工作仍会进入管道一次，因此正常的生命周期
            // 边界可以在不消耗执行能力的情况下持久保留真实的最终状态。
            runNamed(taskId, task);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            if (!removedFromQueue) {
                queuedTasks.decrementAndGet();
            }
            if (running) {
                control.markStopped();
                activeTasks.decrementAndGet();
                concurrencyPermits.release();
            }
            admissionPermits.release();
            control.finish();
            taskControls.remove(taskId, control);
        }
    }

    private void releaseRejected(TaskControl control) {
        queuedTasks.decrementAndGet();
        admissionPermits.release();
        control.finish();
        taskControls.remove(control.taskId(), control);
    }

    private boolean awaitConcurrencyPermit(GenerationTaskExecution execution) throws InterruptedException {
        if (execution == null) {
            concurrencyPermits.acquire();
            return true;
        }
        var context = execution.executionContext();
        while (!context.isCancelled() && !context.isCompleted() && !context.isDeadlineExceeded()) {
            Duration remaining = context.remainingDuration();
            if (remaining.isZero()) {
                return false;
            }
            long waitNanos = Math.min(queuePolicyCheckInterval.toNanos(), remaining.toNanos());
            if (concurrencyPermits.tryAcquire(Math.max(1L, waitNanos), TimeUnit.NANOSECONDS)) {
                return true;
            }
        }
        return false;
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

    int trackedTaskCount() {
        return taskControls.size();
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

    private static final class TaskControl {

        private final String taskId;
        private final AtomicReference<Thread> workerThread = new AtomicReference<>();
        private final AtomicReference<GenerationTaskWatchdog.Registration> watchdogRegistration =
                new AtomicReference<>();
        private final AtomicBoolean running = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean();

        private TaskControl(String taskId) {
            this.taskId = taskId;
        }

        private String taskId() {
            return taskId;
        }

        private void bindWorker(Thread worker) {
            workerThread.set(worker);
        }

        private void bindWatchdog(GenerationTaskWatchdog.Registration registration) {
            if (!watchdogRegistration.compareAndSet(null, registration)) {
                registration.close();
                throw new IllegalStateException("generation task watchdog is already registered");
            }
            if (finished.get() && watchdogRegistration.compareAndSet(registration, null)) {
                registration.close();
            }
        }

        private void markRunning() {
            running.set(true);
        }

        private void markStopped() {
            running.set(false);
        }

        private void interruptIfRunning() {
            Thread worker = workerThread.get();
            if (running.get() && worker != null) {
                worker.interrupt();
            }
        }

        private void finish() {
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            running.set(false);
            GenerationTaskWatchdog.Registration registration = watchdogRegistration.getAndSet(null);
            if (registration != null) {
                registration.close();
            }
            workerThread.set(null);
        }
    }
}
