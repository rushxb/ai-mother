package com.rush.rushaicodemother.orchestration.runtime.task.queue;

import com.rush.rushaicodemother.config.GenerationTaskQueueProperties;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskCommandExecutionService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskDispatchResult;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisGenerationTaskQueueWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");

    private DurableGenerationTaskQueue queue;
    private GenerationTaskCommandExecutionService executionService;
    private DurableGenerationTaskRepository repository;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private RedisGenerationTaskQueueWorker worker;

    @BeforeEach
    void setUp() {
        queue = mock(DurableGenerationTaskQueue.class);
        executionService = mock(GenerationTaskCommandExecutionService.class);
        repository = mock(DurableGenerationTaskRepository.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        properties.setMaxDeliveryAttempts(3);
        worker = new RedisGenerationTaskQueueWorker(
                queue, executionService, repository, runtimeLifecycleService, properties);
    }

    @Test
    void terminalDeliveryMustBeAcknowledgedWithoutExecutingAgain() {
        GenerationTaskQueueDelivery delivery = delivery(1);
        when(executionService.schedule(eq("task-1"), any())).thenReturn(GenerationTaskDispatchResult.TERMINAL);

        worker.process(delivery);

        verify(queue).acknowledge(delivery);
        verify(queue, never()).deadLetter(any(), any());
    }

    @Test
    void duplicateActiveDeliveryMustBeAcknowledged() {
        GenerationTaskQueueDelivery delivery = delivery(1);
        when(executionService.schedule(eq("task-1"), any())).thenReturn(GenerationTaskDispatchResult.ALREADY_ACTIVE);

        worker.process(delivery);

        verify(queue).acknowledge(delivery);
        verify(runtimeLifecycleService, never()).completeUnowned(any(), any(), any());
    }

    @Test
    void retryBeforeMaxAttemptsMustRemainPendingForRedisVisibilityTimeout() {
        GenerationTaskQueueDelivery delivery = delivery(2);
        when(executionService.schedule(eq("task-1"), any())).thenReturn(GenerationTaskDispatchResult.RETRY);

        worker.process(delivery);

        verify(queue, never()).acknowledge(any());
        verify(queue, never()).deadLetter(any(), any());
        verify(runtimeLifecycleService, never()).completeUnowned(any(), any(), any());
    }

    @Test
    void exhaustedRetryMustFailNonTerminalTaskAndDeadLetterDelivery() {
        GenerationTaskQueueDelivery delivery = delivery(3);
        when(executionService.schedule(eq("task-1"), any())).thenReturn(GenerationTaskDispatchResult.RETRY);
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(record(GenerationTaskStatus.QUEUED)));

        worker.process(delivery);

        verify(runtimeLifecycleService).completeUnowned(
                "task-1", GenerationTaskStatus.FAILED, "queue_delivery_exhausted");
        verify(queue).deadLetter(delivery, "task_reservation_unavailable");
    }

    @Test
    void exhaustedExecutionFailureMustDeadLetterWithSanitizedReason() {
        GenerationTaskQueueDelivery delivery = delivery(3);
        doThrow(new IllegalStateException("executor unavailable"))
                .when(executionService).schedule(eq("task-1"), any());
        when(repository.findByTaskId("task-1")).thenReturn(Optional.of(record(GenerationTaskStatus.RUNNING)));

        worker.process(delivery);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(queue).deadLetter(org.mockito.Mockito.eq(delivery), reason.capture());
        assertEquals("IllegalStateException: executor unavailable", reason.getValue());
        verify(runtimeLifecycleService).completeUnowned(
                "task-1", GenerationTaskStatus.FAILED, "queue_delivery_exhausted");
    }

    @Test
    void synchronousCompletionCallbackMustAcknowledgeScheduledDelivery() {
        GenerationTaskQueueDelivery delivery = delivery(1);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return GenerationTaskDispatchResult.SCHEDULED;
        }).when(executionService).schedule(eq("task-1"), any());

        worker.process(delivery);

        verify(queue).acknowledge(delivery);
        verify(queue, never()).deadLetter(any(), any());
    }

    @Test
    void heartbeatMustContinueWhileConsumerIsBlocked() throws Exception {
        GenerationTaskQueueDelivery delivery = delivery(1);
        GenerationTaskQueueProperties properties = new GenerationTaskQueueProperties();
        properties.setDeliveryHeartbeatInterval(Duration.ofMillis(10));
        CountDownLatch readStarted = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch heartbeatObserved = new CountDownLatch(1);
        when(queue.reclaimExpired()).thenReturn(List.of(delivery), List.of());
        when(executionService.schedule(eq("task-1"), any()))
                .thenReturn(GenerationTaskDispatchResult.SCHEDULED);
        when(queue.readNew()).thenAnswer(invocation -> {
            readStarted.countDown();
            try {
                releaseRead.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return List.of();
        });
        doAnswer(invocation -> {
            heartbeatObserved.countDown();
            return null;
        }).when(queue).heartbeat(any());
        RedisGenerationTaskQueueWorker concurrentWorker = new RedisGenerationTaskQueueWorker(
                queue, executionService, repository, runtimeLifecycleService, properties);

        try {
            concurrentWorker.start();
            assertTrue(readStarted.await(1, TimeUnit.SECONDS));
            assertTrue(heartbeatObserved.await(1, TimeUnit.SECONDS));
        } finally {
            releaseRead.countDown();
            concurrentWorker.stop();
        }

        verify(queue, atLeastOnce()).heartbeat(List.of(delivery));
    }

    private GenerationTaskQueueDelivery delivery(long deliveryCount) {
        return new GenerationTaskQueueDelivery("message-1", "task-1", deliveryCount);
    }

    private DurableGenerationTaskRecord record(GenerationTaskStatus status) {
        return new DurableGenerationTaskRecord(
                "task-1", 1L, 2L, 100L, "heavy_generation", status, "queued", "queued",
                NOW, NOW.plusSeconds(600), false, null,
                null, null, null, 0, 1L,
                status.isTerminal() ? NOW.plusSeconds(10) : null, null);
    }
}
