package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationMemoryContextReadExecutorTest {

    @Test
    void enabledExecutorMustRunIndependentReadsConcurrentlyAndPropagateContext() throws Exception {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setParallelReadsEnabled(true);
        properties.setMaxConcurrentReads(3);
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch allStarted = new CountDownLatch(3);
        CountDownLatch release = new CountDownLatch(1);
        MonitorContextHolder.setContext(MonitorContext.builder().taskId("task-memory-read").build());

        try (GenerationMemoryContextReadExecutor executor = new GenerationMemoryContextReadExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(registry),
                executionContextService())) {
            var first = executor.task("recent_tasks", () -> read(allStarted, release));
            var second = executor.task("recent_build_logs", () -> read(allStarted, release));
            var third = executor.task("semantic_memory", () -> read(allStarted, release));
            MonitorContextHolder.clearContext();

            Thread releaser = Thread.ofVirtual().start(() -> {
                try {
                    assertTrue(allStarted.await(2, TimeUnit.SECONDS));
                    release.countDown();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            executor.executeFailFast("task-memory-read", first, second, third);
            releaser.join();
            assertEquals(
                    List.of("task-memory-read", "task-memory-read", "task-memory-read"),
                    List.of(first.result(), second.result(), third.result())
            );
            double readCount = registry.find("generation_memory_context_reads_total")
                    .counters()
                    .stream()
                    .mapToDouble(counter -> counter.count())
                    .sum();
            assertEquals(3.0, readCount);
        } finally {
            release.countDown();
            MonitorContextHolder.clearContext();
        }
    }

    @Test
    void failedReadMustCancelSlowReadWithoutWaitingForSubmissionOrder() throws Exception {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setParallelReadsEnabled(true);
        properties.setMaxConcurrentReads(2);
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        CountDownLatch slowStarted = new CountDownLatch(1);
        CountDownLatch releaseSlowRead = new CountDownLatch(1);
        CountDownLatch slowInterrupted = new CountDownLatch(1);

        try (GenerationMemoryContextReadExecutor executor = new GenerationMemoryContextReadExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                executionContextService())) {
            var slowRead = executor.task("recent_tasks", () -> {
                slowStarted.countDown();
                try {
                    releaseSlowRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    slowInterrupted.countDown();
                    throw interrupted;
                }
                return "slow";
            });
            var failedRead = executor.<String>task("semantic_memory", () -> {
                assertTrue(slowStarted.await(2, TimeUnit.SECONDS));
                throw new IllegalStateException("语义记忆读取失败");
            });

            IllegalStateException failure = assertTimeout(Duration.ofSeconds(2),
                    () -> assertThrows(IllegalStateException.class,
                            () -> executor.executeFailFast(
                                    "task-memory-read-failure", slowRead, failedRead)));

            assertEquals("语义记忆读取失败", failure.getMessage());
            assertTrue(slowInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSlowRead.countDown();
        }
    }

    @Test
    void taskDeadlineMustClampReadTimeoutAndCancelOutstandingRead() throws Exception {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setParallelReadsEnabled(true);
        properties.setMaxConcurrentReads(1);
        properties.setReadTimeout(Duration.ofSeconds(5));
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        GenerationExecutionContextService contextService = executionContextService();
        when(contextService.clampTimeout(
                eq("task-memory-read-deadline"), eq(Duration.ofSeconds(5))))
                .thenReturn(Duration.ofMillis(300));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);

        try (GenerationMemoryContextReadExecutor executor = new GenerationMemoryContextReadExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                contextService)) {
            var slowRead = executor.task("semantic_memory", () -> {
                started.countDown();
                try {
                    Thread.sleep(Duration.ofSeconds(5));
                } catch (InterruptedException interruption) {
                    interrupted.countDown();
                    throw interruption;
                }
                return "slow";
            });

            IllegalStateException failure = assertTimeout(Duration.ofSeconds(2),
                    () -> assertThrows(IllegalStateException.class,
                            () -> executor.executeFailFast(
                                    "task-memory-read-deadline", slowRead)));

            assertEquals("读取生成记忆上下文超时", failure.getMessage());
            assertTrue(started.await(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            verify(contextService).clampTimeout(
                    "task-memory-read-deadline", Duration.ofSeconds(5));
        }
    }

    private String read(CountDownLatch allStarted, CountDownLatch release) throws Exception {
        allStarted.countDown();
        if (!release.await(2, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发读取测试等待超时");
        }
        MonitorContext context = MonitorContextHolder.getContext();
        return context == null ? "" : context.getTaskId();
    }

    private GenerationExecutionContextService executionContextService() {
        GenerationExecutionContextService service = mock(GenerationExecutionContextService.class);
        when(service.clampTimeout(nullable(String.class), any(Duration.class)))
                .thenAnswer(invocation -> invocation.getArgument(1));
        return service;
    }
}
