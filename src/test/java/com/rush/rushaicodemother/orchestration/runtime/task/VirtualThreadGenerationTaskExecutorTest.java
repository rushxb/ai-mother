package com.rush.rushaicodemother.orchestration.runtime.task;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VirtualThreadGenerationTaskExecutorTest {

    private VirtualThreadGenerationTaskExecutor executor;

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdown();
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
    void invalidCapacityAndTimeoutMustFailFast() {
        GenerationTaskExecutorProperties invalidConcurrency = properties(0, 1, Duration.ofSeconds(1));
        GenerationTaskExecutorProperties invalidQueue = properties(1, 0, Duration.ofSeconds(1));
        GenerationTaskExecutorProperties invalidTimeout = properties(1, 1, Duration.ZERO);

        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidConcurrency));
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidQueue));
        assertThrows(IllegalArgumentException.class,
                () -> new VirtualThreadGenerationTaskExecutor(invalidTimeout));
    }

    private VirtualThreadGenerationTaskExecutor executor(
            int maxConcurrency, int queueCapacity, Duration shutdownTimeout) {
        return new VirtualThreadGenerationTaskExecutor(
                properties(maxConcurrency, queueCapacity, shutdownTimeout));
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
}
