package com.rush.rushaicodemother.ai.model.transport;

import com.rush.rushaicodemother.orchestration.runtime.model.GenerationModelInvocationCancellationBridge;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** 执行模型 SSE 请求，并在首个数据块到达前提供可中断的传输层句柄。 */
@Component
public final class CancellableAiStreamingRequestExecutor
        implements AsyncTaskExecutor, AutoCloseable {

    private final GenerationModelInvocationCancellationBridge cancellationBridge;
    private final ExecutorService executor;
    private final AtomicInteger activeTasks = new AtomicInteger();

    @Autowired
    public CancellableAiStreamingRequestExecutor(
            GenerationModelInvocationCancellationBridge cancellationBridge) {
        this(cancellationBridge, Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("ai-streaming-http-", 0).factory()));
    }

    CancellableAiStreamingRequestExecutor(
            GenerationModelInvocationCancellationBridge cancellationBridge,
            ExecutorService executor) {
        this.cancellationBridge = Objects.requireNonNull(
                cancellationBridge, "模型取消桥不能为空");
        this.executor = Objects.requireNonNull(executor, "模型传输执行器不能为空");
    }

    /**
 * 执行{@code Cancellable}AI{@code Streaming}请求处理流程。
 *
 * @param task 任务
 */
    @Override
    public void execute(Runnable task) {
        Objects.requireNonNull(task, "模型传输任务不能为空");
        FutureTask<Void> future = new FutureTask<>(() -> {
            activeTasks.incrementAndGet();
            try {
                task.run();
            } finally {
                activeTasks.decrementAndGet();
            }
            return null;
        });
        cancellationBridge.registerTransportCancellation(() -> future.cancel(true));
        try {
            executor.execute(future);
        } catch (RejectedExecutionException failure) {
            future.cancel(true);
            throw failure;
        }
    }

    int activeTaskCount() {
        return activeTasks.get();
    }

    @PreDestroy
    @Override
    public void close() {
        executor.shutdownNow();
    }
}
