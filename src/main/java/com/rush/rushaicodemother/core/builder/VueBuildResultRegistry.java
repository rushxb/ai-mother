package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.monitor.ProjectBuildCoordinationMetricsCollector;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 合并同一任务、项目和快照的并发 Vue 构建，并仅复用仍与当前产物一致的成功结果。
 *
 * <p>任务级结果负责构建去重，项目级最近成功结果只供预览诊断读取，两者不会混用。</p>
 */
@Component
public class VueBuildResultRegistry {

    private static final String PROJECT_TYPE = "vue";

    private final Map<ReusableKey, Boolean> reusableResults;
    private final Map<ProjectSnapshotKey, VueBuildResult> recentSuccessfulResults;
    private final ConcurrentMap<ReusableKey, CompletableFuture<VueBuildResult>> inFlight =
            new ConcurrentHashMap<>();
    private final ProjectBuildCoordinationMetricsCollector metricsCollector;

    public VueBuildResultRegistry(
            ProjectCommandProperties properties,
            ProjectBuildCoordinationMetricsCollector metricsCollector
    ) {
        Objects.requireNonNull(properties, "项目命令配置不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "项目构建协调指标采集器不能为空");
        int maxEntries = properties.getRecentBuildResultMaxEntries();
        this.reusableResults = boundedLruMap(maxEntries);
        this.recentSuccessfulResults = boundedLruMap(maxEntries);
    }

    VueBuildResult execute(
            String taskId,
            Path projectRoot,
            VueProjectSnapshot snapshot,
            BooleanSupplier reuseGuard,
            Supplier<VueBuildResult> execution
    ) {
        Objects.requireNonNull(reuseGuard, "Vue 构建复用守卫不能为空");
        Objects.requireNonNull(execution, "Vue 构建执行逻辑不能为空");
        ReusableKey key = ReusableKey.of(taskId, projectRoot, snapshot);
        if (key == null) {
            VueBuildResult result = executeAndRecord(execution);
            rememberSuccessful(projectRoot, snapshot, result);
            return result;
        }

        while (true) {
            VueBuildResult reused = reuseIfSafe(key, projectRoot, snapshot, reuseGuard);
            if (reused != null) {
                return reused;
            }

            CompletableFuture<VueBuildResult> ownedFuture = new CompletableFuture<>();
            CompletableFuture<VueBuildResult> existingFuture = inFlight.putIfAbsent(key, ownedFuture);
            if (existingFuture != null) {
                metricsCollector.recordEvent(PROJECT_TYPE, "inflight_joined");
                VueBuildResult joinedResult = awaitAndRecord(existingFuture);
                if (!joinedResult.success()) {
                    return joinedResult;
                }
                inFlight.remove(key, existingFuture);
                continue;
            }

            try {
                VueBuildResult secondCheck = reuseIfSafe(key, projectRoot, snapshot, reuseGuard);
                if (secondCheck != null) {
                    ownedFuture.complete(secondCheck);
                    return secondCheck;
                }

                VueBuildResult result = executeAndRecord(execution);
                if (result.success()) {
                    reusableResults.put(key, Boolean.TRUE);
                    rememberSuccessful(projectRoot, snapshot, result);
                }
                ownedFuture.complete(result);
                return result;
            } catch (Throwable failure) {
                ownedFuture.completeExceptionally(failure);
                throw propagate(failure);
            } finally {
                inFlight.remove(key, ownedFuture);
            }
        }
    }

    void rememberSuccessful(Path projectRoot, VueProjectSnapshot snapshot, VueBuildResult buildResult) {
        if (projectRoot == null || snapshot == null || buildResult == null || !buildResult.success()) {
            return;
        }
        recentSuccessfulResults.put(ProjectSnapshotKey.of(projectRoot, snapshot), buildResult);
    }

    VueBuildResult find(Path projectRoot, VueProjectSnapshot snapshot) {
        if (projectRoot == null || snapshot == null) {
            return null;
        }
        return recentSuccessfulResults.get(ProjectSnapshotKey.of(projectRoot, snapshot));
    }

    int size() {
        return recentSuccessfulResults.size();
    }

    int reusableSize() {
        return reusableResults.size();
    }

    int inFlightSize() {
        return inFlight.size();
    }

    private VueBuildResult reuseIfSafe(
            ReusableKey key,
            Path projectRoot,
            VueProjectSnapshot snapshot,
            BooleanSupplier reuseGuard
    ) {
        if (!Boolean.TRUE.equals(reusableResults.get(key))) {
            return null;
        }
        if (!reuseGuard.getAsBoolean()) {
            reusableResults.remove(key);
            metricsCollector.recordEvent(PROJECT_TYPE, "reuse_rejected");
            return null;
        }
        VueBuildResult reused = VueBuildResult.taskReused(projectRoot.toString());
        rememberSuccessful(projectRoot, snapshot, reused);
        metricsCollector.recordEvent(PROJECT_TYPE, "task_reused");
        return reused;
    }

    private VueBuildResult executeAndRecord(Supplier<VueBuildResult> execution) {
        metricsCollector.recordEvent(PROJECT_TYPE, "execution_started");
        try {
            VueBuildResult result = requireResult(execution.get());
            metricsCollector.recordEvent(
                    PROJECT_TYPE,
                    result.success() ? "execution_success" : "execution_failure"
            );
            return result;
        } catch (Throwable failure) {
            metricsCollector.recordEvent(PROJECT_TYPE, "execution_error");
            throw propagate(failure);
        }
    }

    private VueBuildResult awaitAndRecord(CompletableFuture<VueBuildResult> future) {
        long startedAt = System.nanoTime();
        try {
            VueBuildResult result = await(future);
            metricsCollector.recordJoinWait(
                    PROJECT_TYPE,
                    result.success() ? "success" : "failure",
                    elapsedSince(startedAt)
            );
            return result;
        } catch (RuntimeException exception) {
            metricsCollector.recordJoinWait(
                    PROJECT_TYPE,
                    Thread.currentThread().isInterrupted() ? "interrupted" : "error",
                    elapsedSince(startedAt)
            );
            throw exception;
        }
    }

    private VueBuildResult await(CompletableFuture<VueBuildResult> future) {
        try {
            return requireResult(future.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同任务 Vue 构建时被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            throw propagate(cause);
        }
    }

    private VueBuildResult requireResult(VueBuildResult result) {
        return Objects.requireNonNull(result, "Vue 构建执行结果不能为空");
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Vue 构建执行失败", failure);
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }

    private static <K, V> Map<K, V> boundedLruMap(int maxEntries) {
        return Collections.synchronizedMap(new LinkedHashMap<K, V>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        });
    }

    private record ReusableKey(String taskId, Path projectRoot, VueProjectSnapshot snapshot) {

        private static ReusableKey of(String taskId, Path projectRoot, VueProjectSnapshot snapshot) {
            if (StrUtil.isBlank(taskId) || projectRoot == null || snapshot == null) {
                return null;
            }
            return new ReusableKey(taskId.trim(), projectRoot.toAbsolutePath().normalize(), snapshot);
        }
    }

    private record ProjectSnapshotKey(Path projectRoot, VueProjectSnapshot snapshot) {

        private static ProjectSnapshotKey of(Path projectRoot, VueProjectSnapshot snapshot) {
            return new ProjectSnapshotKey(projectRoot.toAbsolutePath().normalize(), snapshot);
        }
    }
}
