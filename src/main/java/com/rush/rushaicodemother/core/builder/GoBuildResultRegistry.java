package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 复用同一任务内已通过且源码未变化的 Go 构建结果，并合并相同快照的并发构建请求。
 */
@Component
public class GoBuildResultRegistry {

    private final Map<CacheKey, Boolean> successfulResults;
    private final ConcurrentMap<CacheKey, CompletableFuture<GoBuildResult>> inFlight =
            new ConcurrentHashMap<>();

    public GoBuildResultRegistry(ProjectCommandProperties properties) {
        Objects.requireNonNull(properties, "项目命令配置不能为空");
        int maxEntries = properties.getRecentBuildResultMaxEntries();
        this.successfulResults = Collections.synchronizedMap(new LinkedHashMap<>(32, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, Boolean> eldest) {
                return size() > maxEntries;
            }
        });
    }

    GoBuildResult execute(
            String taskId,
            Path projectRoot,
            GoProjectSnapshot snapshot,
            Supplier<GoBuildResult> execution
    ) {
        Objects.requireNonNull(execution, "Go 构建执行逻辑不能为空");
        CacheKey key = CacheKey.of(taskId, projectRoot, snapshot);
        if (key == null) {
            return requireResult(execution.get());
        }
        if (Boolean.TRUE.equals(successfulResults.get(key))) {
            return GoBuildResult.reused(projectRoot.toString());
        }

        CompletableFuture<GoBuildResult> ownedFuture = new CompletableFuture<>();
        CompletableFuture<GoBuildResult> existingFuture = inFlight.putIfAbsent(key, ownedFuture);
        if (existingFuture != null) {
            GoBuildResult result = await(existingFuture);
            return result.success() ? GoBuildResult.reused(projectRoot.toString()) : result;
        }

        try {
            if (Boolean.TRUE.equals(successfulResults.get(key))) {
                GoBuildResult reused = GoBuildResult.reused(projectRoot.toString());
                ownedFuture.complete(reused);
                return reused;
            }
            GoBuildResult result = requireResult(execution.get());
            if (result.success()) {
                successfulResults.put(key, Boolean.TRUE);
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

    int size() {
        return successfulResults.size();
    }

    int inFlightSize() {
        return inFlight.size();
    }

    private GoBuildResult await(CompletableFuture<GoBuildResult> future) {
        try {
            return requireResult(future.join());
        } catch (CompletionException exception) {
            throw propagate(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private GoBuildResult requireResult(GoBuildResult result) {
        return Objects.requireNonNull(result, "Go 构建执行结果不能为空");
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("Go 构建执行失败", failure);
    }

    private record CacheKey(String taskId, Path projectRoot, GoProjectSnapshot snapshot) {

        private static CacheKey of(String taskId, Path projectRoot, GoProjectSnapshot snapshot) {
            if (StrUtil.isBlank(taskId) || projectRoot == null || snapshot == null) {
                return null;
            }
            return new CacheKey(
                    taskId.trim(),
                    projectRoot.toAbsolutePath().normalize(),
                    snapshot
            );
        }
    }
}
