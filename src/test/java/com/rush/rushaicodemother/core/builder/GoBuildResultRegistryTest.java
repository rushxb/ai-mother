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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private GoBuildResultRegistry registry(int maxEntries) {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setRecentBuildResultMaxEntries(maxEntries);
        return new GoBuildResultRegistry(properties);
    }

    private GoBuildResult success() {
        return new GoBuildResult(true, "done", projectRoot.toString(), "通过", null);
    }
}
