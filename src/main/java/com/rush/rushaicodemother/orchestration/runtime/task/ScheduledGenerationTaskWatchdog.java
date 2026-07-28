package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 每个任务的绝对截止日期看门狗。
 *
 * <p>看门狗通过会话取消活动模型句柄并中断worker
 * 虚拟线程。持久租赁协调器独立地停止更新已取消的上下文，
 * 因此，在有界租约宽限期之后，不合作的工作人员也会失去其写入围栏。</p>
 */
@Slf4j
@Component
public class ScheduledGenerationTaskWatchdog implements GenerationTaskWatchdog {

    static final String DEADLINE_REASON = "deadline_exceeded";

    private final ScheduledExecutorService scheduler;
    private final ExecutorService deadlineActionExecutor;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    /** 创建{@code Scheduled}生成任务{@code Watchdog}实例并完成必要的依赖和初始状态设置。 */
    public ScheduledGenerationTaskWatchdog() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform()
                        .name("generation-task-watchdog")
                        .daemon(true)
                        .unstarted(runnable));
        this.deadlineActionExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("generation-task-deadline-action-", 0).factory());
    }

    /**
 * 返回{@code watch}。
 *
 * @param execution 执行
 * @param interruptRunningTask {@code interruptRunningTask} 对应的调用参数
 * @return {@code Scheduled}生成任务{@code Watchdog}
 */
    @Override
    public Registration watch(GenerationTaskExecution execution, Runnable interruptRunningTask) {
        Objects.requireNonNull(execution, "execution");
        Objects.requireNonNull(interruptRunningTask, "interruptRunningTask");
        if (shuttingDown.get()) {
            throw new IllegalStateException("generation task watchdog is shutting down");
        }
        DeadlineRegistration registration = new DeadlineRegistration(execution, interruptRunningTask);
        registration.arm();
        return registration;
    }

    @PreDestroy
    void shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            scheduler.shutdownNow();
            deadlineActionExecutor.shutdownNow();
        }
    }

    private final class DeadlineRegistration implements Registration {

        private final GenerationTaskExecution execution;
        private final Runnable interruptRunningTask;
        private final AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        private final AtomicReference<RegistrationState> state =
                new AtomicReference<>(RegistrationState.ACTIVE);

        private DeadlineRegistration(GenerationTaskExecution execution, Runnable interruptRunningTask) {
            this.execution = execution;
            this.interruptRunningTask = interruptRunningTask;
        }

        /** 处理{@code arm}。 */
        private void arm() {
            GenerationExecutionContext context = execution.executionContext();
            if (state.get() != RegistrationState.ACTIVE || context.isCompleted() || shuttingDown.get()) {
                return;
            }
            Duration remaining = context.remainingDuration();
            long delayNanos = remaining.isZero() ? 0L : Math.max(1L, remaining.toNanos());
            ScheduledFuture<?> next = scheduler.schedule(this::checkDeadline, delayNanos, TimeUnit.NANOSECONDS);
            ScheduledFuture<?> previous = scheduled.getAndSet(next);
            if (previous != null) {
                previous.cancel(false);
            }
            if (state.get() != RegistrationState.ACTIVE && scheduled.compareAndSet(next, null)) {
                next.cancel(false);
            }
        }

        /** 检查截止时间的当前状态。 */
        private void checkDeadline() {
            GenerationExecutionContext context = execution.executionContext();
            if (state.get() != RegistrationState.ACTIVE || context.isCompleted() || shuttingDown.get()) {
                return;
            }
            if (!context.isDeadlineExceeded()) {
                arm();
                return;
            }
            if (!state.compareAndSet(RegistrationState.ACTIVE, RegistrationState.TRIGGERED)) {
                return;
            }

            log.warn("生成任务达到硬截止时间，开始取消 worker，taskId: {}",
                    execution.taskId());
            context.cancel(DEADLINE_REASON);
            dispatchDeadlineAction(this::interruptWorker);
            dispatchDeadlineAction(this::cancelSession);
        }

        /** 分发截止时间动作。 */
        private void dispatchDeadlineAction(Runnable action) {
            try {
                deadlineActionExecutor.execute(action);
            } catch (RejectedExecutionException rejected) {
                if (!shuttingDown.get()) {
                    log.error("生成任务截止动作提交失败，taskId: {}",
                            execution.taskId(), LogExceptionSanitizer.sanitize(rejected));
                }
            }
        }

        /** 取消会话。 */
        private void cancelSession() {
            try {
                execution.session().cancel(DEADLINE_REASON);
            } catch (RuntimeException cancellationFailure) {
                log.error("生成会话截止取消失败，taskId: {}",
                        execution.taskId(), LogExceptionSanitizer.sanitize(cancellationFailure));
            }
        }

        /** 处理{@code interrupt}工作器。 */
        private void interruptWorker() {
            try {
                interruptRunningTask.run();
            } catch (RuntimeException interruptionFailure) {
                log.error("生成 worker 截止中断失败，taskId: {}",
                        execution.taskId(), LogExceptionSanitizer.sanitize(interruptionFailure));
            }
        }

        /** 关闭截止时间{@code Registration}并释放资源。 */
        @Override
        public void close() {
            if (!state.compareAndSet(RegistrationState.ACTIVE, RegistrationState.CLOSED)) {
                return;
            }
            ScheduledFuture<?> future = scheduled.getAndSet(null);
            if (future != null) {
                future.cancel(false);
            }
        }
    }

    private enum RegistrationState {
        ACTIVE,
        TRIGGERED,
        CLOSED
    }
}
