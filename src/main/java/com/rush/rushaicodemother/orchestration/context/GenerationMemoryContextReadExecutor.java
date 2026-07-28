package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
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
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** 为生成记忆上下文提供有界、可观测的只读并发执行。 */
@Component
public class GenerationMemoryContextReadExecutor implements AutoCloseable {

    private final GenerationMemoryContextProperties properties;
    private final GenerationContextPreparationMetricsCollector metricsCollector;
    private final GenerationExecutionContextService executionContextService;
    private final ExecutorService executor;
    private final Semaphore concurrencyPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GenerationMemoryContextReadExecutor(
            GenerationMemoryContextProperties properties,
            GenerationContextPreparationMetricsCollector metricsCollector,
            GenerationExecutionContextService executionContextService
    ) {
        this.properties = Objects.requireNonNull(properties, "生成记忆上下文配置不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成上下文指标收集器不能为空");
        this.executionContextService = Objects.requireNonNull(
                executionContextService, "生成执行上下文服务不能为空");
        this.concurrencyPermits = new Semaphore(properties.getMaxConcurrentReads(), true);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("generation-memory-read-", 0).factory());
    }

    public boolean parallelReadsEnabled() {
        return properties.isParallelReadsEnabled();
    }

    /**
 * 读取{@code Sequential}。
 *
 * @param source 来源数据
 * @param read {@code read} 对应的调用参数
 * @return {@code Sequential}
 */
    public <T> T readSequential(String taskId, String source, Supplier<T> read) {
        Objects.requireNonNull(read, "记忆读取操作不能为空");
        ReadTask<T> task = task(source, read::get);
        execute(taskId, "sequential", task);
        return task.result();
    }

    /**
 * 返回任务。
 *
 * @param source 来源数据
 * @param read {@code read} 对应的调用参数
 * @return 生成记忆上下文{@code Read}
 */
    public <T> ReadTask<T> task(String source, Callable<T> read) {
        Objects.requireNonNull(read, "记忆读取操作不能为空");
        return new ReadTask<>(source, read, copyContext(MonitorContextHolder.getContext()));
    }

    /** 执行{@code Fail}{@code Fast}处理流程。 */
    public void executeFailFast(String taskId, ReadTask<?>... tasks) {
        execute(taskId, "parallel", tasks);
    }

    private void execute(String taskId, String mode, ReadTask<?>... tasks) {
        Objects.requireNonNull(tasks, "记忆读取任务不能为空");
        if (tasks.length == 0) {
            return;
        }
        if (closed.get()) {
            throw new RejectedExecutionException("生成记忆上下文执行器已关闭");
        }
        executionContextService.assertCanContinue(taskId);
        Duration timeout = executionContextService.clampTimeout(taskId, properties.getReadTimeout());
        long deadlineNanos = deadlineAfter(timeout);
        ExecutorCompletionService<Void> completionService = new ExecutorCompletionService<>(executor);
        List<Future<Void>> futures = new ArrayList<>(tasks.length);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            for (ReadTask<?> task : tasks) {
                executionContextService.assertCanContinue(taskId);
                ReadTask<?> checkedTask = Objects.requireNonNull(task, "记忆读取任务不能为空");
                futures.add(completionService.submit(
                        () -> executeTask(
                                taskId,
                                mode,
                                checkedTask,
                                copyContext(checkedTask.capturedContext),
                                deadlineNanos
                        )));
            }
            for (int completed = 0; completed < tasks.length; completed++) {
                long remainingNanos = remainingNanos(deadlineNanos);
                if (remainingNanos <= 0L) {
                    throw new TimeoutException("读取生成记忆上下文超时");
                }
                Future<Void> completedFuture = completionService.poll(
                        remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    throw new TimeoutException("读取生成记忆上下文超时");
                }
                completedFuture.get();
                executionContextService.assertCanContinue(taskId);
            }
        } catch (InterruptedException interrupted) {
            cancel(futures);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("读取生成记忆上下文被中断", interrupted);
        } catch (TimeoutException timeoutFailure) {
            cancel(futures);
            executionContextService.assertCanContinue(taskId);
            throw new IllegalStateException("读取生成记忆上下文超时", timeoutFailure);
        } catch (ExecutionException executionFailure) {
            cancel(futures);
            throw propagate(executionFailure);
        } catch (RuntimeException | Error failure) {
            cancel(futures);
            throw failure;
        }
    }

    private <T> Void executeTask(String taskId,
                                 String mode,
                                 ReadTask<T> task,
                                 MonitorContext capturedContext,
                                 long deadlineNanos) throws Exception {
        T result = runRead(taskId, mode, task.source, capturedContext, task.read, deadlineNanos);
        task.complete(result);
        return null;
    }

    /** 返回{@code propagate}。 */
    private RuntimeException propagate(ExecutionException executionFailure) {
        Throwable cause = executionFailure.getCause();
        if (cause instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        if (cause instanceof TimeoutException timeoutFailure) {
            return new IllegalStateException("读取生成记忆上下文超时", timeoutFailure);
        }
        return new IllegalStateException("读取生成记忆上下文失败", cause);
    }

    /** 取消生成记忆上下文{@code Read}。 */
    private void cancel(List<? extends Future<?>> futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        }
    }

    /** 运行{@code Parallel}处理流程。 */
    private <T> T runRead(String taskId,
                          String mode,
                          String source,
                          MonitorContext capturedContext,
                          Callable<T> read,
                          long deadlineNanos) throws Exception {
        long started = System.nanoTime();
        String status = "success";
        boolean acquired = false;
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            installContext(capturedContext);
            executionContextService.assertCanContinue(taskId);
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0L
                    || !concurrencyPermits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("等待生成记忆上下文读取许可超时");
            }
            acquired = true;
            executionContextService.assertCanContinue(taskId);
            T result = read.call();
            executionContextService.assertCanContinue(taskId);
            return result;
        } catch (InterruptedException interrupted) {
            status = "interrupted";
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (TimeoutException timeoutFailure) {
            status = "timeout";
            throw timeoutFailure;
        } catch (RuntimeException | Error failure) {
            status = executionContextService.shouldStop(taskId) ? "cancelled" : "failed";
            throw failure;
        } catch (Exception failure) {
            status = "failed";
            throw failure;
        } finally {
            if (acquired) {
                concurrencyPermits.release();
            }
            restoreContext(previousContext);
            metricsCollector.recordMemoryRead(
                    source, mode, status, elapsed(started));
        }
    }

    private long deadlineAfter(Duration timeout) {
        long now = System.nanoTime();
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
        return Long.MAX_VALUE - now < timeoutNanos ? Long.MAX_VALUE : now + timeoutNanos;
    }

    private long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
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

        /**
 * 返回结果。
 *
 * @return {@code Read}任务
 */
        public T result() {
            if (!completed) {
                throw new IllegalStateException("记忆读取任务尚未成功完成");
            }
            return result;
        }
    }

    /** 关闭生成记忆上下文{@code Read}并释放资源。 */
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
