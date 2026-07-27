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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledGenerationTaskWatchdogTest {

    private final ScheduledGenerationTaskWatchdog watchdog = new ScheduledGenerationTaskWatchdog();

    @AfterEach
    void tearDown() {
        watchdog.shutdown();
    }

    @Test
    void deadlineMustCancelModelHandleAndInvokeWorkerInterruptionExactlyOnce() throws Exception {
        GenerationTaskExecution execution = execution("task-watchdog", Duration.ofMillis(120));
        CountDownLatch modelCancelled = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger interruptionCount = new AtomicInteger();
        execution.session().setCancellationHandle(modelCancelled::countDown);

        watchdog.watch(execution, () -> {
            interruptionCount.incrementAndGet();
            interrupted.countDown();
        });

        assertTrue(modelCancelled.await(2, TimeUnit.SECONDS));
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertEquals(1, interruptionCount.get());
        assertEquals(ScheduledGenerationTaskWatchdog.DEADLINE_REASON,
                execution.executionContext().cancellationReason());
    }

    @Test
    void closedRegistrationMustNeverCancelTheTask() throws Exception {
        GenerationTaskExecution execution = execution("task-watchdog-closed", Duration.ofMillis(150));
        AtomicInteger interruptions = new AtomicInteger();

        GenerationTaskWatchdog.Registration registration =
                watchdog.watch(execution, interruptions::incrementAndGet);
        registration.close();
        Thread.sleep(250L);

        assertFalse(execution.executionContext().isCancelled());
        assertEquals(0, interruptions.get());
    }

    @Test
    void blockedModelCancellationMustNotDelayWorkerInterruptOrOtherDeadlines() throws Exception {
        GenerationTaskExecution first = execution("task-watchdog-blocked", Duration.ofMillis(60));
        GenerationTaskExecution second = execution("task-watchdog-independent", Duration.ofMillis(180));
        CountDownLatch firstCancellationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCancellation = new CountDownLatch(1);
        CountDownLatch firstCancellationExited = new CountDownLatch(1);
        CountDownLatch firstWorkerInterrupted = new CountDownLatch(1);
        CountDownLatch secondModelCancelled = new CountDownLatch(1);
        CountDownLatch secondWorkerInterrupted = new CountDownLatch(1);
        first.session().setCancellationHandle(() -> {
            firstCancellationEntered.countDown();
            try {
                releaseFirstCancellation.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                firstCancellationExited.countDown();
            }
        });
        second.session().setCancellationHandle(secondModelCancelled::countDown);

        watchdog.watch(first, firstWorkerInterrupted::countDown);
        watchdog.watch(second, secondWorkerInterrupted::countDown);

        try {
            assertTrue(firstCancellationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(firstWorkerInterrupted.await(1, TimeUnit.SECONDS),
                    "模型取消阻塞时仍必须及时中断当前任务 worker");
            assertTrue(secondModelCancelled.await(1, TimeUnit.SECONDS),
                    "一个任务的阻塞取消不能拖延其他任务的硬截止时间");
            assertTrue(secondWorkerInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirstCancellation.countDown();
        }

        assertTrue(firstCancellationExited.await(1, TimeUnit.SECONDS));
    }

    private GenerationTaskExecution execution(String taskId, Duration timeout) {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setTaskTimeout(timeout);
        properties.setModelCallTimeout(timeout.minusMillis(20));
        properties.setMinimumOperationTimeout(Duration.ofMillis(1));
        properties.setFirstPreviewCompletionReserve(Duration.ofMillis(1));
        GenerationExecutionContext context =
                new GenerationExecutionContextService(properties).start(taskId, 1L, 2L);
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 1L);
        context.bindExecutionFence(fence);
        return new GenerationTaskExecution(
                taskId, new GenerationSession(null, context), context, fence, Instant.now());
    }
}
