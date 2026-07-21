package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-task absolute-deadline watchdog.
 *
 * <p>The watchdog cancels the active model handle through the session and interrupts the worker
 * virtual thread. The durable lease coordinator independently stops renewing cancelled contexts,
 * so a non-cooperative worker also loses its write fence after the bounded lease grace period.</p>
 */
@Slf4j
@Component
public class ScheduledGenerationTaskWatchdog implements GenerationTaskWatchdog {

    static final String DEADLINE_REASON = "deadline_exceeded";

    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    public ScheduledGenerationTaskWatchdog() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable ->
                Thread.ofPlatform()
                        .name("generation-task-watchdog")
                        .daemon(true)
                        .unstarted(runnable));
    }

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

            log.warn("Generation task hard deadline reached; cancelling worker, taskId: {}",
                    execution.taskId());
            context.cancel(DEADLINE_REASON);
            try {
                execution.session().cancel(DEADLINE_REASON);
            } catch (RuntimeException cancellationFailure) {
                log.error("Generation session deadline cancellation failed, taskId: {}",
                        execution.taskId(), LogExceptionSanitizer.sanitize(cancellationFailure));
            }
            try {
                interruptRunningTask.run();
            } catch (RuntimeException interruptionFailure) {
                log.error("Generation worker deadline interruption failed, taskId: {}",
                        execution.taskId(), LogExceptionSanitizer.sanitize(interruptionFailure));
            }
        }

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
