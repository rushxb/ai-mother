package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GoBuildResultRegistryTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldMergeConcurrentBuildsForSameTaskAndSnapshot() throws Exception {
        GoBuildResultRegistry registry = registry(20);
        GoProjectSnapshot snapshot = new GoProjectSnapshot("same");
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch callersReady = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<GoBuildResult>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    callersReady.countDown();
                    start.await();
                    return registry.execute("task", projectRoot, snapshot, () -> {
                        executions.incrementAndGet();
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("并发构建测试被中断", exception);
                        }
                        return success();
                    });
                }));
            }
            callersReady.await();
            start.countDown();

            for (Future<GoBuildResult> future : futures) {
                assertTrue(future.get().success());
            }
        }

        assertEquals(1, executions.get());
        assertEquals(1, registry.size());
        assertEquals(0, registry.inFlightSize());
    }

    @Test
    void shouldNotRetainFailuresAndShouldBoundSuccessfulEntries() {
        GoBuildResultRegistry registry = registry(2);
        AtomicInteger failedExecutions = new AtomicInteger();
        GoProjectSnapshot failedSnapshot = new GoProjectSnapshot("failed");

        registry.execute("task", projectRoot, failedSnapshot, () -> {
            failedExecutions.incrementAndGet();
            return GoBuildResult.invalid(projectRoot.toString(), "失败");
        });
        registry.execute("task", projectRoot, failedSnapshot, () -> {
            failedExecutions.incrementAndGet();
            return GoBuildResult.invalid(projectRoot.toString(), "仍然失败");
        });
        assertEquals(2, failedExecutions.get());
        assertEquals(0, registry.size());

        registry.execute("task", projectRoot, new GoProjectSnapshot("one"), this::success);
        registry.execute("task", projectRoot, new GoProjectSnapshot("two"), this::success);
        registry.execute("task", projectRoot, new GoProjectSnapshot("three"), this::success);

        assertEquals(2, registry.size());
    }

    @Test
    void interruptedJoinedBuildMustStopWaitingWithoutCancellingOwner() throws Exception {
        GoBuildResultRegistry registry = registry(20);
        GoProjectSnapshot snapshot = new GoProjectSnapshot("interruptible");
        CountDownLatch ownerStarted = new CountDownLatch(1);
        CountDownLatch releaseOwner = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        CountDownLatch waiterFinished = new CountDownLatch(1);
        AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
        AtomicBoolean waiterInterruptRestored = new AtomicBoolean();

        Thread owner = Thread.ofVirtual().start(() -> registry.execute(
                "task-interruptible",
                projectRoot,
                snapshot,
                () -> {
                    ownerStarted.countDown();
                    try {
                        releaseOwner.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("构建所有者不应被等待方中断", interrupted);
                    }
                    return success();
                }
        ));
        assertTrue(ownerStarted.await(1, TimeUnit.SECONDS), "构建所有者未开始执行");

        Thread waiter = Thread.ofVirtual().start(() -> {
            waiterStarted.countDown();
            try {
                registry.execute(
                        "task-interruptible",
                        projectRoot,
                        snapshot,
                        () -> {
                            throw new AssertionError("等待方不应重复执行相同构建");
                        }
                );
            } catch (Throwable failure) {
                waiterFailure.set(failure);
                waiterInterruptRestored.set(Thread.currentThread().isInterrupted());
            } finally {
                waiterFinished.countDown();
            }
        });
        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS), "构建等待方未开始执行");

        try {
            waiter.interrupt();
            assertTrue(waiterFinished.await(500, TimeUnit.MILLISECONDS),
                    "等待共享 Go 构建时忽略了任务取消中断");
            IllegalStateException failure = assertInstanceOf(
                    IllegalStateException.class,
                    waiterFailure.get()
            );
            assertEquals("等待同任务 Go 构建时被中断", failure.getMessage());
            assertTrue(waiterInterruptRestored.get(), "等待方退出前未恢复线程中断状态");
        } finally {
            releaseOwner.countDown();
            waiter.interrupt();
            waiter.join(1000);
            owner.join(1000);
        }

        assertEquals(0, registry.inFlightSize());
    }

    private GoBuildResultRegistry registry(int maxEntries) {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setRecentBuildResultMaxEntries(maxEntries);
        return new GoBuildResultRegistry(properties);
    }

    private GoBuildResult success() {
        return new GoBuildResult(true, "done", projectRoot.toString(), "通过", null);
    }
}
