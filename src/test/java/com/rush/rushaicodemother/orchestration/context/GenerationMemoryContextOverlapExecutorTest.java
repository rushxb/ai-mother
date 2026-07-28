package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.context.GenerationMemoryContextOverlapExecutor.MemoryContextHandle;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

class GenerationMemoryContextOverlapExecutorTest {

    @Test
    void enabledExecutorMustStartAsynchronouslyAndPropagateMonitorContext() throws Exception {
        GenerationMemoryContextProperties properties = enabledProperties(2);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MonitorContextHolder.setContext(MonitorContext.builder().taskId("task-overlap-context").build());

        try (GenerationMemoryContextOverlapExecutor executor = executor(properties, registry);
             MemoryContextHandle handle = executor.start("task-overlap-context", () -> {
                 started.countDown();
                 await(release);
                 MonitorContext context = MonitorContextHolder.getContext();
                 return context == null ? "" : context.getTaskId();
             })) {
            assertTrue(started.await(1, TimeUnit.SECONDS));
            MonitorContextHolder.clearContext();
            release.countDown();

            assertEquals("task-overlap-context", handle.resolve());
            assertEquals(1.0, registry.find("generation_memory_context_preparation_overlap_total")
                    .tag("phase", "execution")
                    .tag("status", "success")
                    .counter()
                    .count());
        } finally {
            release.countDown();
            MonitorContextHolder.clearContext();
        }
    }

    @Test
    void failureMustPropagateFromJoinWithoutChangingTheCause() {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        try (GenerationMemoryContextOverlapExecutor executor = executor(
                properties, new SimpleMeterRegistry());
             MemoryContextHandle handle = executor.start(
                     "task-overlap-failure",
                     () -> {
                         throw new IllegalStateException("语义记忆读取失败");
                     })) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class, handle::resolve);

            assertEquals("语义记忆读取失败", failure.getMessage());
        }
    }

    @Test
    void closingUnusedHandleMustInterruptBackgroundMemoryBuild() throws Exception {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (GenerationMemoryContextOverlapExecutor executor = executor(
                properties, new SimpleMeterRegistry())) {
            MemoryContextHandle handle = executor.start("task-overlap-cancel", () -> {
                started.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException failure) {
                    interrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("记忆构建被中断", failure);
                }
                return "memory";
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));

            handle.close();

            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
        }
    }

    @Test
    void joinTimeoutMustCancelTheBackgroundMemoryBuild() throws Exception {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        properties.setPreparationOverlapTimeout(Duration.ofMillis(80));
        CountDownLatch interrupted = new CountDownLatch(1);

        try (GenerationMemoryContextOverlapExecutor executor = executor(
                properties, new SimpleMeterRegistry());
             MemoryContextHandle handle = executor.start("task-overlap-timeout", () -> {
                 try {
                     Thread.sleep(Duration.ofSeconds(5));
                 } catch (InterruptedException failure) {
                     interrupted.countDown();
                     Thread.currentThread().interrupt();
                     throw new IllegalStateException("记忆构建被中断", failure);
                 }
                 return "memory";
             })) {
            IllegalStateException failure = assertTimeout(
                    Duration.ofSeconds(1),
                    () -> assertThrows(IllegalStateException.class, handle::resolve));

            assertEquals("等待生成记忆上下文超时", failure.getMessage());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void saturatedAdmissionMustDeferWithoutBlockingThePreparationThread() throws Exception {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);

        try (GenerationMemoryContextOverlapExecutor executor = executor(
                properties, new SimpleMeterRegistry());
             MemoryContextHandle firstHandle = executor.start("task-overlap-first", () -> {
                 firstStarted.countDown();
                 await(releaseFirst);
                  return "first";
              })) {
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            try (MemoryContextHandle secondHandle = assertTimeout(
                    Duration.ofMillis(300),
                    () -> executor.start("task-overlap-second", () -> {
                        secondStarted.countDown();
                        return "second";
                    }))) {
                assertFalse(secondStarted.await(100, TimeUnit.MILLISECONDS));
                releaseFirst.countDown();
                assertEquals("first", firstHandle.resolve());
                assertEquals("second", secondHandle.resolve());
                assertTrue(secondStarted.await(1, TimeUnit.SECONDS));
            }
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void deferredBuildMustHonorCancellationBeforeResolveAndLeavePermitReusable() throws Exception {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicBoolean deferredBuilderCalled = new AtomicBoolean();
        GenerationExecutionContextService contextService = contextService(properties);
        AtomicInteger secondChecks = new AtomicInteger();
        doAnswer(invocation -> {
            if ("task-overlap-cancelled-waiter".equals(invocation.getArgument(0))) {
                if (secondChecks.incrementAndGet() >= 2) {
                    throw new IllegalStateException("等待者已取消");
                }
            }
            return null;
        }).when(contextService).assertCanContinue(nullable(String.class));

        try (GenerationMemoryContextOverlapExecutor executor = new GenerationMemoryContextOverlapExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                contextService);
             MemoryContextHandle firstHandle = executor.start("task-overlap-holder", () -> {
                 firstStarted.countDown();
                 await(releaseFirst);
                 return "first";
              })) {
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            try (MemoryContextHandle deferredHandle = executor.start(
                    "task-overlap-cancelled-waiter", () -> {
                        deferredBuilderCalled.set(true);
                        return "cancelled";
                    })) {
                IllegalStateException failure = assertThrows(
                        IllegalStateException.class, deferredHandle::resolve);
                assertEquals("等待者已取消", failure.getMessage());
                assertFalse(deferredBuilderCalled.get());
            }
            releaseFirst.countDown();
            assertEquals("first", firstHandle.resolve());
            try (MemoryContextHandle thirdHandle = executor.start(
                    "task-overlap-after-cancellation", () -> "third")) {
                assertEquals("third", thirdHandle.resolve());
            }
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void cancellationAfterImmediateAdmissionMustReleaseThePermit() {
        GenerationMemoryContextProperties properties = enabledProperties(1);
        GenerationExecutionContextService contextService = contextService(properties);
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if ("task-overlap-immediate-cancellation".equals(invocation.getArgument(0))
                    && checks.incrementAndGet() >= 2) {
                throw new IllegalStateException("任务在取得许可后已取消");
            }
            return null;
        }).when(contextService).assertCanContinue(nullable(String.class));

        try (GenerationMemoryContextOverlapExecutor executor = new GenerationMemoryContextOverlapExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(new SimpleMeterRegistry()),
                contextService)) {
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> executor.start("task-overlap-immediate-cancellation", () -> "cancelled")
            );

            assertEquals("任务在取得许可后已取消", failure.getMessage());
            try (MemoryContextHandle handle = executor.start(
                    "task-overlap-after-immediate-cancellation", () -> "next")) {
                assertEquals("next", handle.resolve());
            }
        }
    }

    private GenerationMemoryContextOverlapExecutor executor(
            GenerationMemoryContextProperties properties,
            SimpleMeterRegistry registry
    ) {
        return new GenerationMemoryContextOverlapExecutor(
                properties,
                new GenerationContextPreparationMetricsCollector(registry),
                contextService(properties)
        );
    }

    private GenerationExecutionContextService contextService(
            GenerationMemoryContextProperties properties
    ) {
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.clampTimeout(
                nullable(String.class), any(Duration.class)))
                .thenReturn(properties.getPreparationOverlapTimeout());
        return executionContextService;
    }

    private GenerationMemoryContextProperties enabledProperties(int maxConcurrentOverlaps) {
        GenerationMemoryContextProperties properties = new GenerationMemoryContextProperties();
        properties.setPreparationOverlapEnabled(true);
        properties.setMaxConcurrentPreparationOverlaps(maxConcurrentOverlaps);
        properties.setPreparationOverlapTimeout(Duration.ofSeconds(2));
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试等待记忆构建超时");
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待记忆构建被中断", failure);
        }
    }
}
