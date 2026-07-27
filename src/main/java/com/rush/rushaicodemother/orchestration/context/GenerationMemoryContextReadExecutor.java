package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 为生成记忆上下文提供有界、可观测的只读并发执行。 */
@Component
public class GenerationMemoryContextReadExecutor implements AutoCloseable {

    private final GenerationMemoryContextProperties properties;
    private final GenerationContextPreparationMetricsCollector metricsCollector;
    private final ExecutorService executor;
    private final Semaphore concurrencyPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GenerationMemoryContextReadExecutor(
            GenerationMemoryContextProperties properties,
            GenerationContextPreparationMetricsCollector metricsCollector
    ) {
        this.properties = Objects.requireNonNull(properties, "生成记忆上下文配置不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成上下文指标收集器不能为空");
        this.concurrencyPermits = new Semaphore(properties.getMaxConcurrentReads(), true);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("generation-memory-read-", 0).factory());
    }

    public boolean parallelReadsEnabled() {
        return properties.isParallelReadsEnabled();
    }

    public <T> T readSequential(String source, Supplier<T> read) {
        Objects.requireNonNull(read, "记忆读取操作不能为空");
        long started = System.nanoTime();
        String status = "success";
        try {
            return read.get();
        } catch (RuntimeException | Error failure) {
            status = "failed";
            throw failure;
        } finally {
            metricsCollector.recordMemoryRead(
                    source, "sequential", status, elapsed(started));
        }
    }

    public <T> ReadTask<T> task(String source, Callable<T> read) {
        Objects.requireNonNull(read, "记忆读取操作不能为空");
        return new ReadTask<>(source, read, copyContext(MonitorContextHolder.getContext()));
    }

    public void executeFailFast(ReadTask<?>... tasks) {
        Objects.requireNonNull(tasks, "记忆读取任务不能为空");
        if (tasks.length == 0) {
            return;
        }
        if (closed.get()) {
            throw new RejectedExecutionException("生成记忆上下文执行器已关闭");
        }
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(tasks.length);
        try {
            for (ReadTask<?> task : tasks) {
                ReadTask<?> checkedTask = Objects.requireNonNull(task, "记忆读取任务不能为空");
                futures.add(completionService.submit(
                        () -> executeTask(checkedTask, copyContext(checkedTask.capturedContext))));
            }
            for (int completed = 0; completed < tasks.length; completed++) {
                completionService.take().get();
            }
        } catch (InterruptedException interrupted) {
            cancel(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取生成记忆上下文被中断", interrupted);
        } catch (ExecutionException executionFailure) {
            cancel(futures);
            throw propagate(executionFailure);
        } catch (RuntimeException | Error failure) {
            cancel(futures);
            throw failure;
        }
    }

    private <T> Void executeTask(ReadTask<T> task, MonitorContext capturedContext) throws Exception {
        T result = runParallel(task.source, capturedContext, task.read);
        task.complete(result);
        return null;
    }

    private RuntimeException propagate(ExecutionException executionFailure) {
        Throwable cause = executionFailure.getCause();
        if (cause instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("读取生成记忆上下文失败", cause);
    }

    private void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    private <T> T runParallel(String source,
                              MonitorContext capturedContext,
                              Callable<T> read) throws Exception {
        long started = System.nanoTime();
        String status = "success";
        boolean acquired = false;
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            installContext(capturedContext);
            concurrencyPermits.acquire();
            acquired = true;
            return read.call();
        } catch (InterruptedException interrupted) {
            status = "interrupted";
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception | Error failure) {
            status = "failed";
            throw failure;
        } finally {
            if (acquired) {
                concurrencyPermits.release();
            }
            restoreContext(previousContext);
            metricsCollector.recordMemoryRead(
                    source, "parallel", status, elapsed(started));
        }
    }

    private Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private MonitorContext copyContext(MonitorContext context) {
        if (context == null) {
            return null;
        }
        return MonitorContext.builder()
                .userId(context.getUserId())
                .appId(context.getAppId())
                .taskId(context.getTaskId())
                .build();
    }

    private void installContext(MonitorContext context) {
        if (context == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(context);
        }
    }

    private void restoreContext(MonitorContext context) {
        if (context == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(context);
        }
    }

    public static final class ReadTask<T> {

        private final String source;
        private final Callable<T> read;
        private final MonitorContext capturedContext;
        private T result;
        private boolean completed;

        private ReadTask(String source, Callable<T> read, MonitorContext capturedContext) {
            this.source = source;
            this.read = read;
            this.capturedContext = capturedContext;
        }

        private void complete(T result) {
            this.result = result;
            this.completed = true;
        }

        public T result() {
            if (!completed) {
                throw new IllegalStateException("记忆读取任务尚未成功完成");
            }
            return result;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(
                    properties.getShutdownTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
