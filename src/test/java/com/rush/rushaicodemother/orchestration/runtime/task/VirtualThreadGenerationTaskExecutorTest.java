package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadGenerationTaskExecutorTest {

    private VirtualThreadGenerationTaskExecutor executor;
    private ScheduledGenerationTaskWatchdog watchdog;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
        }
        if (watchdog != null) {
            watchdog.shutdown();
        }
    }

    @Test
    void blankTaskIdAndNullTaskMustBeRejectedBeforeSubmission() {
        executor = executor(1, 1, Duration.ofSeconds(1));

        assertThrows(IllegalArgumentException.class, () -> executor.execute(" ", () -> { }));
        assertThrows(NullPointerException.class, () -> executor.execute("task-valid", null));
        assertEquals(0, executor.activeTaskCount());
        assertEquals(0, executor.queuedTaskCount());
    }

    @Test
    void boundedCapacityMustRejectThirdTaskAndDrainQueuedTaskAfterRelease() throws Exception {
        executor = executor(1, 1, Duration.ofSeconds(1));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondCompleted = new CountDownLatch(1);

        executor.execute("task-one", () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        executor.execute("task-two", secondCompleted::countDown);
        assertEquals(1, executor.queuedTaskCount());

        GenerationTaskCapacityExceededException rejection = assertThrows(
                GenerationTaskCapacityExceededException.class,
                () -> executor.execute("task-three", () -> { })
        );
        assertEquals("当前生成任务较多，请稍后重试", rejection.getPublicMessage());

        releaseFirst.countDown();
        assertTrue(secondCompleted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void shutdownMustRejectNewTasksWithoutSilentlyDroppingThem() {
        executor = executor(1, 1, Duration.ofSeconds(1));

        executor.shutdown();

        assertThrows(GenerationTaskCapacityExceededException.class,
                () -> executor.execute("task-after-shutdown", () -> { }));
    }

    @Test
    void expiredQueuedTaskMustTerminalizeWithoutWaitingForExecutionCapacity() throws Exception {
        executor = executor(1, 1, Duration.ofSeconds(1));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch expiredTaskInvoked = new CountDownLatch(1);

        executor.execute("task-blocking", () -> {
            firstStarted.countDown();
            await(releaseFirst);
        });
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS));

        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofMillis(100));
        runtimeProperties.setModelCallTimeout(Duration.ofMillis(50));
        runtimeProperties.setMinimumOperationTimeout(Duration.ofMillis(1));
        runtimeProperties.setFirstPreviewCompletionReserve(Duration.ofMillis(1));
        GenerationExecutionContext context =
                new GenerationExecutionContextService(runtimeProperties).start("task-expiring", 1L, 2L);
        GenerationExecutionFence fence =
                new GenerationExecutionFence(context.taskId(), "worker-a", 3L);
        context.bindExecutionFence(fence);
        GenerationTaskExecution execution = new GenerationTaskExecution(
                context.taskId(), new GenerationSession(null, context), context, fence, context.startedAt());

        executor.execute(execution, expiredTaskInvoked::countDown);

        assertTrue(expiredTaskInvoked.await(1, TimeUnit.SECONDS));
        assertEquals(1, executor.activeTaskCount());
        releaseFirst.countDown();
    }

    @Test
    void runningManagedTaskMustBeCancelledAndInterruptedAtItsAbsoluteDeadline() throws Exception {
        executor = executor(1, 1, Duration.ofSeconds(1));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofMillis(150));
        runtimeProperties.setModelCallTimeout(Duration.ofMillis(100));
        runtimeProperties.setMinimumOperationTimeout(Duration.ofMillis(1));
        runtimeProperties.setFirstPreviewCompletionReserve(Duration.ofMillis(1));
        GenerationExecutionContext context =
                new GenerationExecutionContextService(runtimeProperties)
                        .start("task-hard-deadline", 1L, 2L);
        GenerationExecutionFence fence =
                new GenerationExecutionFence(context.taskId(), "worker-a", 4L);
        context.bindExecutionFence(fence);
        GenerationSession session = new GenerationSession(null, context);
        GenerationTaskExecution execution = new GenerationTaskExecution(
                context.taskId(), session, context, fence, Instant.now());

        executor.execute(execution, () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expected) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });

        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
        assertTrue(context.isCancelled());
        assertEquals(ScheduledGenerationTaskWatchdog.DEADLINE_REASON, context.cancellationReason());
        awaitTrackedTasks(0);
    }

    @Test
    void invalidCapacityAndTimeoutMustFailFast() {
        GenerationTaskExecutorProperties invalidConcurrency = properties(0, 1, Duration.ofSeconds(1));
        GenerationTaskExecutorProperties invalidQueue = properties(1, 0, Duration.ofSeconds(1));
        GenerationTaskExecutorProperties invalidTimeout = properties(1, 1, Duration.ZERO);
        GenerationTaskExecutorProperties invalidPolicyInterval = properties(1, 1, Duration.ofSeconds(1));
        invalidPolicyInterval.setQueuePolicyCheckInterval(Duration.ZERO);
        watchdog = new ScheduledGenerationTaskWatchdog();

        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidConcurrency, watchdog));
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidQueue, watchdog));
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidTimeout, watchdog));
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidPolicyInterval, watchdog));
    }

    private VirtualThreadGenerationTaskExecutor executor(
            int maxConcurrency, int queueCapacity, Duration shutdownTimeout) {
        watchdog = new ScheduledGenerationTaskWatchdog();
        return new VirtualThreadGenerationTaskExecutor(
                properties(maxConcurrency, queueCapacity, shutdownTimeout), watchdog);
    }

    private GenerationTaskExecutorProperties properties(
            int maxConcurrency, int queueCapacity, Duration shutdownTimeout) {
        GenerationTaskExecutorProperties properties = new GenerationTaskExecutorProperties();
        properties.setMaxConcurrency(maxConcurrency);
        properties.setQueueCapacity(queueCapacity);
        properties.setShutdownTimeout(shutdownTimeout);
        return properties;
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void awaitTrackedTasks(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (executor.trackedTaskCount() != expected && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertEquals(expected, executor.trackedTaskCount());
    }
}
