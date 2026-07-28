package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 为 CREATE 规格推理提供有界虚拟线程，并显式传播模型监控上下文。 */
@Component
public class CreateSpecTaskExecutor {

    private final ExecutorService executor;
    private final Semaphore permits;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /** 兼容聚焦单元测试；直接执行，不创建后台线程。 */
    CreateSpecTaskExecutor() {
        this.executor = null;
        this.permits = null;
    }

    /**
 * 创建{@code Spec}任务执行器实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 */
    @Autowired
    public CreateSpecTaskExecutor(GenerationTaskExecutorProperties properties) {
        Objects.requireNonNull(properties, "生成任务执行器配置不能为空");
        int maxConcurrency = properties.getMaxConcurrency();
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("CREATE 规格并发数必须大于 0");
        }
        this.permits = new Semaphore(maxConcurrency, true);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("create-spec-", 0).factory());
    }

    /**
 * 校验并提交当前请求。
 *
 * @param monitorContext {@code monitorContext} 对应的调用参数
 * @param task 任务
 * @return 异步处理结果
 */
    public <T> Future<T> submit(MonitorContext monitorContext, Callable<T> task) {
        Objects.requireNonNull(task, "CREATE 规格任务不能为空");
        MonitorContext contextSnapshot = copy(monitorContext);
        if (executor == null) {
            try {
                return CompletableFuture.completedFuture(callWithContext(contextSnapshot, task));
            } catch (Exception failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        if (shuttingDown.get() || !permits.tryAcquire()) {
            throw new RejectedExecutionException("CREATE 规格并行执行器已饱和");
        }
        try {
            return executor.submit(() -> {
                try {
                    return callWithContext(contextSnapshot, task);
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException submissionFailure) {
            permits.release();
            throw submissionFailure;
        }
    }

    /** 返回调用并上下文。 */
    private <T> T callWithContext(MonitorContext context, Callable<T> task) throws Exception {
        MonitorContext previousContext = MonitorContextHolder.getContext();
        if (context == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(context);
        }
        try {
            return task.call();
        } finally {
            if (previousContext == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previousContext);
            }
        }
    }

    private MonitorContext copy(MonitorContext context) {
        if (context == null) {
            return null;
        }
        return new MonitorContext(context.getUserId(), context.getAppId(), context.getTaskId());
    }

    /** 处理{@code shutdown}。 */
    @PreDestroy
    void shutdown() {
        if (executor == null || !shuttingDown.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
