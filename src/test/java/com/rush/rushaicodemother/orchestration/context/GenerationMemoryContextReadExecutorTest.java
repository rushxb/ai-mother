package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
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
                new GenerationContextPreparationMetricsCollector(registry))) {
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
            executor.executeFailFast(first, second, third);
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
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()))) {
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
                            () -> executor.executeFailFast(slowRead, failedRead)));

            assertEquals("语义记忆读取失败", failure.getMessage());
            assertTrue(slowInterrupted.await(1, TimeUnit.SECONDS));
        } finally {
            releaseSlowRead.countDown();
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
}
