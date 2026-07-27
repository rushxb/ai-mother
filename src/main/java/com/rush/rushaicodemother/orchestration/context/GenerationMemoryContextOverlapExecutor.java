package com.rush.rushaicodemother.orchestration.context;

import com.rush.rushaicodemother.config.GenerationMemoryContextProperties;
import com.rush.rushaicodemother.monitor.GenerationContextPreparationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 在受控并发与任务截止时间内，让记忆构建和编排准备重叠执行。 */
@Component
public class GenerationMemoryContextOverlapExecutor implements AutoCloseable {

    private final GenerationMemoryContextProperties properties;
    private final GenerationContextPreparationMetricsCollector metricsCollector;
    private final GenerationExecutionContextService executionContextService;
    private final ExecutorService executor;
    private final Semaphore admissionPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    public GenerationMemoryContextOverlapExecutor(
            GenerationMemoryContextProperties properties,
            GenerationContextPreparationMetricsCollector metricsCollector,
            GenerationExecutionContextService executionContextService
    ) {
        this.properties = Objects.requireNonNull(properties, "生成记忆上下文配置不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成上下文指标收集器不能为空");
        this.executionContextService = Objects.requireNonNull(executionContextService, "生成执行上下文服务不能为空");
        this.admissionPermits = new Semaphore(properties.getMaxConcurrentPreparationOverlaps(), true);
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("generation-memory-overlap-", 0).factory());
    }

    public MemoryContextHandle start(String taskId, Supplier<String> memoryContextBuilder) {
        Objects.requireNonNull(memoryContextBuilder, "生成记忆上下文构建器不能为空");
        if (!properties.isPreparationOverlapEnabled()) {
            return MemoryContextHandle.completed(memoryContextBuilder.get());
        }
        if (closed.get()) {
            throw new RejectedExecutionException("生成记忆上下文重叠执行器已关闭");
        }
        executionContextService.assertCanContinue(taskId);
        Duration timeout = executionContextService.clampTimeout(
                taskId, properties.getPreparationOverlapTimeout());
        long deadlineNanos = deadlineAfter(timeout);
        acquireAdmission(taskId, deadlineNanos);

        TaskState taskState = new TaskState(admissionPermits);
        MonitorContext capturedContext = copyContext(MonitorContextHolder.getContext());
        try {
            Future<String> future = executor.submit(() -> execute(
                    taskId, memoryContextBuilder, capturedContext, taskState));
            return new AsyncMemoryContextHandle(
                    taskId,
                    future,
                    taskState,
                    deadlineNanos,
                    executionContextService,
                    metricsCollector
            );
        } catch (RuntimeException | Error failure) {
            taskState.cancelBeforeStart();
            throw failure;
        }
    }

    private String execute(String taskId,
                           Supplier<String> memoryContextBuilder,
                           MonitorContext capturedContext,
                           TaskState taskState) {
        if (!taskState.start()) {
            throw new CancellationException("生成记忆上下文构建已取消");
        }
        long started = System.nanoTime();
        String status = "success";
        MonitorContext previousContext = MonitorContextHolder.getContext();
        try {
            installContext(capturedContext);
            executionContextService.assertCanContinue(taskId);
            String result = memoryContextBuilder.get();
            executionContextService.assertCanContinue(taskId);
            return result;
        } catch (RuntimeException | Error failure) {
            status = Thread.currentThread().isInterrupted()
                    || executionContextService.shouldStop(taskId) ? "cancelled" : "failed";
            throw failure;
        } finally {
            restoreContext(previousContext);
            taskState.finish();
            metricsCollector.recordMemoryPreparationOverlap(
                    "execution", status, elapsed(started));
        }
    }

    private void acquireAdmission(String taskId, long deadlineNanos) {
        if (admissionPermits.tryAcquire()) {
            try {
                executionContextService.assertCanContinue(taskId);
            } catch (RuntimeException | Error failure) {
                admissionPermits.release();
                throw failure;
            }
            return;
        }
        long started = System.nanoTime();
        String status = "success";
        boolean acquired = false;
        try {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0L
                    || !admissionPermits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                status = "timeout";
                executionContextService.assertCanContinue(taskId);
                throw new IllegalStateException("等待生成记忆上下文并发许可超时");
            }
            acquired = true;
            executionContextService.assertCanContinue(taskId);
        } catch (InterruptedException interrupted) {
            status = "interrupted";
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待生成记忆上下文并发许可被中断", interrupted);
        } catch (RuntimeException | Error failure) {
            if ("success".equals(status)) {
                status = "failed";
            }
            if (acquired) {
                admissionPermits.release();
            }
            throw failure;
        } finally {
            metricsCollector.recordMemoryPreparationOverlap(
                    "admission", status, elapsed(started));
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

    private static long remainingNanos(long deadlineNanos) {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - started));
    }

    private static MonitorContext copyContext(MonitorContext context) {
        if (context == null) {
            return null;
        }
        return MonitorContext.builder()
                .userId(context.getUserId())
                .appId(context.getAppId())
                .taskId(context.getTaskId())
                .build();
    }

    private static void installContext(MonitorContext context) {
        if (context == null) {
            MonitorContextHolder.clearContext();
        } else {
            MonitorContextHolder.setContext(context);
        }
    }

    private static void restoreContext(MonitorContext context) {
        installContext(context);
    }

    public interface MemoryContextHandle extends AutoCloseable {

        String resolve();

        @Override
        void close();

        static MemoryContextHandle completed(String value) {
            return new MemoryContextHandle() {
                @Override
                public String resolve() {
                    return value;
                }

                @Override
                public void close() {
                    // 同步结果不持有后台资源。
                }
            };
        }
    }

    private static final class AsyncMemoryContextHandle implements MemoryContextHandle {

        private final String taskId;
        private final Future<String> future;
        private final TaskState taskState;
        private final long deadlineNanos;
        private final GenerationExecutionContextService executionContextService;
        private final GenerationContextPreparationMetricsCollector metricsCollector;

        private AsyncMemoryContextHandle(
                String taskId,
                Future<String> future,
                TaskState taskState,
                long deadlineNanos,
                GenerationExecutionContextService executionContextService,
                GenerationContextPreparationMetricsCollector metricsCollector
        ) {
            this.taskId = taskId;
            this.future = future;
            this.taskState = taskState;
            this.deadlineNanos = deadlineNanos;
            this.executionContextService = executionContextService;
            this.metricsCollector = metricsCollector;
        }

        @Override
        public String resolve() {
            long started = System.nanoTime();
            String status = "success";
            try {
                executionContextService.assertCanContinue(taskId);
                long remainingNanos = remainingNanos(deadlineNanos);
                if (remainingNanos <= 0L) {
                    throw new TimeoutException("生成记忆上下文准备已超时");
                }
                String result = future.get(remainingNanos, TimeUnit.NANOSECONDS);
                executionContextService.assertCanContinue(taskId);
                return result;
            } catch (InterruptedException interrupted) {
                status = "interrupted";
                cancel();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待生成记忆上下文被中断", interrupted);
            } catch (TimeoutException timeout) {
                status = "timeout";
                cancel();
                executionContextService.assertCanContinue(taskId);
                throw new IllegalStateException("等待生成记忆上下文超时", timeout);
            } catch (CancellationException cancelled) {
                status = "cancelled";
                executionContextService.assertCanContinue(taskId);
                throw new IllegalStateException("生成记忆上下文构建已取消", cancelled);
            } catch (ExecutionException executionFailure) {
                status = "failed";
                throw propagate(executionFailure);
            } catch (RuntimeException | Error failure) {
                status = executionContextService.shouldStop(taskId) ? "cancelled" : "failed";
                throw failure;
            } finally {
                metricsCollector.recordMemoryPreparationOverlap(
                        "join", status, elapsed(started));
            }
        }

        private RuntimeException propagate(ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause();
            if (cause instanceof RuntimeException runtimeFailure) {
                return runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            return new IllegalStateException("生成记忆上下文构建失败", cause);
        }

        private void cancel() {
            future.cancel(true);
            taskState.cancelBeforeStart();
        }

        @Override
        public void close() {
            if (!future.isDone()) {
                cancel();
            }
        }
    }

    private static final class TaskState {

        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final Semaphore admissionPermits;
        private final AtomicInteger state = new AtomicInteger(QUEUED);

        private TaskState(Semaphore admissionPermits) {
            this.admissionPermits = admissionPermits;
        }

        private boolean start() {
            return state.compareAndSet(QUEUED, RUNNING);
        }

        private void finish() {
            if (state.compareAndSet(RUNNING, FINISHED)) {
                admissionPermits.release();
            }
        }

        private void cancelBeforeStart() {
            if (state.compareAndSet(QUEUED, FINISHED)) {
                admissionPermits.release();
            }
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
