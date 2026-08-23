package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 监督阻塞式模型调用，使任务取消和截止时间不再被 Provider 调用窗口吞掉。
 *
 * <p>同步模型 SDK 不暴露统一的传输取消句柄，因此模型调用在独立虚拟线程执行；
 * 调用方只等待一个很短的状态窗口，并持续复用任务执行上下文的权威取消与截止时间。
 * 取消 Future 会中断支持中断的 HTTP 传输；即使第三方传输暂不响应中断，托管 worker
 * 也能及时退出并进入统一终态。</p>
 */
@Component
public final class GenerationSynchronousModelCallSupervisor implements AutoCloseable {

    private static final Duration DEFAULT_STATUS_POLL_INTERVAL = Duration.ofMillis(50);

    private final ExecutorService modelCallExecutor;
    private final long statusPollNanos;

    public GenerationSynchronousModelCallSupervisor() {
        this(
                Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("generation-sync-model-", 0).factory()),
                DEFAULT_STATUS_POLL_INTERVAL
        );
    }

    GenerationSynchronousModelCallSupervisor(ExecutorService modelCallExecutor,
                                              Duration statusPollInterval) {
        this.modelCallExecutor = Objects.requireNonNull(
                modelCallExecutor, "同步模型调用执行器不能为空");
        if (statusPollInterval == null
                || statusPollInterval.isZero()
                || statusPollInterval.isNegative()) {
            throw new IllegalArgumentException("同步模型状态检查周期必须大于 0");
        }
        this.statusPollNanos = statusPollInterval.toNanos();
    }

    /** 执行一次受任务取消、截止时间和 worker 中断约束的阻塞模型调用。 */
    public <T> T execute(GenerationExecutionContext context, Supplier<T> invocation) {
        Objects.requireNonNull(context, "生成执行上下文不能为空");
        Objects.requireNonNull(invocation, "同步模型调用不能为空");
        context.assertCanContinue();
        MonitorContext capturedMonitorContext = MonitorContextHolder.getContext();
        Future<T> modelCall = modelCallExecutor.submit(
                () -> invokeWithMonitorContext(capturedMonitorContext, invocation));
        try {
            while (true) {
                context.assertCanContinue();
                try {
                    T result = modelCall.get(waitNanos(context), TimeUnit.NANOSECONDS);
                    context.assertCanContinue();
                    return result;
                } catch (TimeoutException waitingForModel) {
                    // 短等待只用于重新检查任务状态，Provider 自身仍受任务级模型超时约束。
                }
            }
        } catch (GenerationExecutionPolicyException policyFailure) {
            modelCall.cancel(true);
            throw policyFailure;
        } catch (InterruptedException interrupted) {
            modelCall.cancel(true);
            Thread.currentThread().interrupt();
            throw new GenerationExecutionCancelledException("worker_interrupted");
        } catch (ExecutionException modelFailure) {
            context.assertCanContinue();
            throw propagate(modelFailure.getCause());
        } finally {
            if (!modelCall.isDone()) {
                modelCall.cancel(true);
            }
        }
    }

    private long waitNanos(GenerationExecutionContext context) {
        long remainingNanos = Math.max(1L, context.remainingDuration().toNanos());
        return Math.min(statusPollNanos, remainingNanos);
    }

    private <T> T invokeWithMonitorContext(MonitorContext captured,
                                           Supplier<T> invocation) {
        MonitorContext previous = MonitorContextHolder.getContext();
        try {
            if (captured == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(captured);
            }
            return invocation.get();
        } finally {
            if (previous == null) {
                MonitorContextHolder.clearContext();
            } else {
                MonitorContextHolder.setContext(previous);
            }
        }
    }

    private RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("同步模型调用异常终止", failure);
    }

    /** 应用关闭时中断仍未完成的同步模型调用。 */
    @Override
    public void close() {
        modelCallExecutor.shutdownNow();
    }
}
