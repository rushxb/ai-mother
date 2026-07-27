package com.rush.rushaicodemother.core.builder;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.monitor.ProjectBuildCoordinationMetricsCollector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VueBuildResultRegistryTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldMergeConcurrentBuildsForSameTaskAndSnapshot() throws Exception {
        VueBuildResultRegistry registry = registry(20);
        VueProjectSnapshot snapshot = snapshot("same");
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch callersReady = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<VueBuildResult>> futures = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                futures.add(executor.submit(() -> {
                    callersReady.countDown();
                    start.await();
                    return registry.execute("task", projectRoot, snapshot, () -> true, () -> {
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

            for (Future<VueBuildResult> future : futures) {
                assertTrue(future.get().success());
            }
        }

        assertEquals(1, executions.get());
        assertEquals(1, registry.reusableSize());
        assertEquals(1, registry.size());
        assertEquals(0, registry.inFlightSize());
    }

    @Test
    void shouldNotRetainFailuresAndShouldBoundSuccessfulEntries() {
        VueBuildResultRegistry registry = registry(2);
        AtomicInteger failedExecutions = new AtomicInteger();
        VueProjectSnapshot failedSnapshot = snapshot("failed");

        registry.execute("task", projectRoot, failedSnapshot, () -> true, () -> {
            failedExecutions.incrementAndGet();
            return VueBuildResult.invalid(projectRoot.toString(), "失败");
        });
        registry.execute("task", projectRoot, failedSnapshot, () -> true, () -> {
            failedExecutions.incrementAndGet();
            return VueBuildResult.invalid(projectRoot.toString(), "仍然失败");
        });

        assertEquals(2, failedExecutions.get());
        assertEquals(0, registry.reusableSize());
        assertEquals(0, registry.size());
        assertNull(registry.find(projectRoot, failedSnapshot));

        registry.execute("task", projectRoot, snapshot("one"), () -> true, this::success);
        registry.execute("task", projectRoot, snapshot("two"), () -> true, this::success);
        registry.execute("task", projectRoot, snapshot("three"), () -> true, this::success);

        assertEquals(2, registry.reusableSize());
        assertEquals(2, registry.size());
    }

    @Test
    void shouldExecuteAgainWhenArtifactGuardRejectsCachedResult() {
        VueBuildResultRegistry registry = registry(20);
        VueProjectSnapshot snapshot = snapshot("guard");
        AtomicInteger executions = new AtomicInteger();

        registry.execute("task", projectRoot, snapshot, () -> true, () -> {
            executions.incrementAndGet();
            return success();
        });
        VueBuildResult second = registry.execute("task", projectRoot, snapshot, () -> false, () -> {
            executions.incrementAndGet();
            return success();
        });

        assertEquals(2, executions.get());
        assertEquals("done", second.stage());
    }

    @Test
    void shouldIsolateReusableResultsByTask() {
        VueBuildResultRegistry registry = registry(20);
        VueProjectSnapshot snapshot = snapshot("task");
        AtomicInteger executions = new AtomicInteger();

        registry.execute("task-one", projectRoot, snapshot, () -> true, () -> {
            executions.incrementAndGet();
            return success();
        });
        registry.execute("task-two", projectRoot, snapshot, () -> true, () -> {
            executions.incrementAndGet();
            return success();
        });

        assertEquals(2, executions.get());
        assertEquals(2, registry.reusableSize());
    }

    @Test
    void shouldDisableExecutionReuseWithoutTaskIdentity() {
        VueBuildResultRegistry registry = registry(20);
        VueProjectSnapshot snapshot = snapshot("legacy");
        AtomicInteger executions = new AtomicInteger();

        registry.execute(null, projectRoot, snapshot, () -> true, () -> {
            executions.incrementAndGet();
            return success();
        });
        registry.execute(" ", projectRoot, snapshot, () -> true, () -> {
            executions.incrementAndGet();
            return success();
        });

        assertEquals(2, executions.get());
        assertEquals(0, registry.reusableSize());
        assertEquals(1, registry.size());
    }

    @Test
    void shouldPublishExecutionAndReuseMetrics() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        VueBuildResultRegistry registry = new VueBuildResultRegistry(
                properties,
                new ProjectBuildCoordinationMetricsCollector(meterRegistry)
        );
        VueProjectSnapshot snapshot = snapshot("metrics");

        registry.execute("task", projectRoot, snapshot, () -> true, this::success);
        registry.execute("task", projectRoot, snapshot, () -> true, this::success);

        assertEquals(1, eventCount(meterRegistry, "execution_started"), 0.001);
        assertEquals(1, eventCount(meterRegistry, "execution_success"), 0.001);
        assertEquals(1, eventCount(meterRegistry, "task_reused"), 0.001);
    }

    private VueBuildResultRegistry registry(int maxEntries) {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setRecentBuildResultMaxEntries(maxEntries);
        return new VueBuildResultRegistry(
                properties,
                ProjectBuildCoordinationMetricsCollector.noOp()
        );
    }

    private VueProjectSnapshot snapshot(String suffix) {
        return new VueProjectSnapshot("dependency-" + suffix, "critical-" + suffix, "presentation-" + suffix);
    }

    private VueBuildResult success() {
        return VueBuildResult.success(
                projectRoot.toString(),
                VueBuildCommandResult.skipped("pnpm install", "已缓存"),
                VueBuildCommandResult.success("pnpm run build", 0, "通过")
        );
    }

    private double eventCount(SimpleMeterRegistry registry, String event) {
        return registry.find("generation_project_build_coordination_total")
                .tag("project_type", "vue")
                .tag("event", event)
                .counter()
                .count();
    }
}
