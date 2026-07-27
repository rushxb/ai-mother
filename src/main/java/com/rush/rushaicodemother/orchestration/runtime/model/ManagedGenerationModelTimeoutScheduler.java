package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 统一管理物理模型调用的首信号计时任务。 */
@Component
public final class ManagedGenerationModelTimeoutScheduler
        implements GenerationModelTimeoutScheduler, AutoCloseable {

    private final ScheduledThreadPoolExecutor scheduler;

    public ManagedGenerationModelTimeoutScheduler() {
        scheduler = new ScheduledThreadPoolExecutor(1, runnable -> Thread.ofPlatform()
                .name("generation-model-first-signal-timeout")
                .daemon(true)
                .unstarted(runnable));
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    @Override
    public GenerationCancellationHandle schedule(Duration delay, Runnable timeoutAction) {
        Objects.requireNonNull(timeoutAction, "模型超时动作不能为空");
        if (delay == null || delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("模型超时时间必须大于 0");
        }
        var future = scheduler.schedule(
                timeoutAction, delay.toNanos(), TimeUnit.NANOSECONDS);
        return () -> future.cancel(false);
    }

    @PreDestroy
    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
