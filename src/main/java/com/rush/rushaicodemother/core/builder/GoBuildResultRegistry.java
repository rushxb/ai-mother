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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/**
 * 复用同一任务内已通过且源码未变化的 Go 构建结果，并合并相同快照的并发构建请求。
 */
@Component
public class GoBuildResultRegistry {

    private final Map<CacheKey, Boolean> successfulResults;
    private final ConcurrentMap<CacheKey, CompletableFuture<GoBuildResult>> inFlight =
            new ConcurrentHashMap<>();

    /**
 * 创建{@code Go}构建结果注册器实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 */
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

    /** 执行{@code Go}构建结果注册器处理流程。 */
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

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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

    /** 等待{@code Go}构建结果注册器完成。 */
    private GoBuildResult await(CompletableFuture<GoBuildResult> future) {
        try {
            // join 不响应线程中断，会让已取消或超时的生成 worker 继续被旧构建占用。
            // 这里只终止当前等待方；共享构建仍由所有者执行，不能取消 future 误伤其他调用者。
            return requireResult(future.get());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待同任务 Go 构建时被中断", exception);
        } catch (ExecutionException exception) {
            throw propagate(exception.getCause() == null ? exception : exception.getCause());
        }
    }

    private GoBuildResult requireResult(GoBuildResult result) {
        return Objects.requireNonNull(result, "Go 构建执行结果不能为空");
    }

    /** 返回{@code propagate}。 */
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
