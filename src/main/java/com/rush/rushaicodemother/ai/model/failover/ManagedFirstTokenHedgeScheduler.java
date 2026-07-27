package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 使用单个守护计时线程和虚拟线程启动到期的影子请求。 */
@Slf4j
@Component
public class ManagedFirstTokenHedgeScheduler implements FirstTokenHedgeScheduler {

    private final ScheduledThreadPoolExecutor timerExecutor;
    private final ExecutorService launchExecutor;

    public ManagedFirstTokenHedgeScheduler() {
        this.timerExecutor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform().daemon(true).name("ai-first-token-hedge-timer-", 0).factory()
        );
        this.timerExecutor.setRemoveOnCancelPolicy(true);
        this.timerExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.launchExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @Override
    public GenerationCancellationHandle schedule(Duration delay, Runnable task) {
        Objects.requireNonNull(delay, "首 Token 对冲延迟不能为空");
        Objects.requireNonNull(task, "首 Token 对冲任务不能为空");
        if (delay.isZero() || delay.isNegative()) {
            throw new IllegalArgumentException("首 Token 对冲延迟必须大于 0");
        }
        ScheduledFuture<?> future = timerExecutor.schedule(
                () -> launch(task),
                delay.toNanos(),
                TimeUnit.NANOSECONDS
        );
        return () -> future.cancel(false);
    }

    private void launch(Runnable task) {
        try {
            launchExecutor.execute(task);
        } catch (RejectedExecutionException shutdownRace) {
            log.debug("首 Token 对冲调度器已关闭，忽略到期任务");
        }
    }

    @PreDestroy
    public void close() {
        timerExecutor.shutdownNow();
        launchExecutor.shutdownNow();
    }
}
