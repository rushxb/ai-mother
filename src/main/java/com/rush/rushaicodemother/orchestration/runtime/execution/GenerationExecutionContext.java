package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe control plane for one generation task.
 *
 * <p>The context is passed explicitly to asynchronous boundaries instead of relying on a
 * ThreadLocal, because generation work moves between request threads, Reactor callbacks and
 * virtual threads.</p>
 */
public final class GenerationExecutionContext {

    private final String taskId;
    private final Long appId;
    private final Long userId;
    private final Instant startedAt;
    private final Instant deadlineAt;
    private final GenerationExecutionLimits limits;
    private final Clock clock;
    private final EnumMap<GenerationBudgetKind, AtomicInteger> usages;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> cancellationReason = new AtomicReference<>();
    private final AtomicReference<String> terminalStatus = new AtomicReference<>();

    public GenerationExecutionContext(
            String taskId,
            Long appId,
            Long userId,
            Instant startedAt,
            GenerationExecutionLimits limits,
            Clock clock
    ) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
        this.taskId = taskId;
        this.appId = appId;
        this.userId = userId;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deadlineAt = startedAt.plus(limits.taskTimeout());
        this.usages = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            usages.put(kind, new AtomicInteger());
        }
    }

    public String taskId() {
        return taskId;
    }

    public Long appId() {
        return appId;
    }

    public Long userId() {
        return userId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public GenerationExecutionLimits limits() {
        return limits;
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public String cancellationReason() {
        return cancellationReason.get();
    }

    public boolean isCompleted() {
        return terminalStatus.get() != null;
    }

    public boolean isDeadlineExceeded() {
        return !clock.instant().isBefore(deadlineAt);
    }

    public void assertCanContinue() {
        if (cancelled.get()) {
            throw new GenerationExecutionCancelledException(cancellationReason.get());
        }
        String status = terminalStatus.get();
        if (status != null) {
            throw new GenerationExecutionPolicyException("生成任务已经结束，status=" + status);
        }
        if (isDeadlineExceeded()) {
            throw new GenerationDeadlineExceededException(taskId);
        }
    }

    public Duration remainingDuration() {
        Duration remaining = Duration.between(clock.instant(), deadlineAt);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    /**
     * Restricts an operation timeout to the remaining task deadline.
     */
    public Duration clampTimeout(Duration requestedTimeout) {
        assertCanContinue();
        if (requestedTimeout == null || requestedTimeout.isZero() || requestedTimeout.isNegative()) {
            throw new IllegalArgumentException("操作超时必须大于 0");
        }
        Duration remaining = remainingDuration();
        if (remaining.compareTo(limits.minimumOperationTimeout()) < 0) {
            throw new GenerationDeadlineExceededException(taskId);
        }
        return requestedTimeout.compareTo(remaining) <= 0 ? requestedTimeout : remaining;
    }

    /**
     * Atomically reserves one unit before the operation starts.
     */
    public int consume(GenerationBudgetKind kind) {
        Objects.requireNonNull(kind, "kind");
        assertCanContinue();
        AtomicInteger usage = usages.get(kind);
        int limit = limits.limit(kind);
        while (true) {
            int current = usage.get();
            if (current >= limit) {
                throw new GenerationBudgetExceededException(kind, limit);
            }
            if (usage.compareAndSet(current, current + 1)) {
                return current + 1;
            }
        }
    }

    public boolean hasRemainingBudget(GenerationBudgetKind kind) {
        Objects.requireNonNull(kind, "kind");
        return !isCancelled()
                && !isCompleted()
                && !isDeadlineExceeded()
                && used(kind) < limit(kind);
    }

    public int used(GenerationBudgetKind kind) {
        AtomicInteger usage = usages.get(kind);
        if (usage == null) {
            throw new IllegalArgumentException("未知预算类型：" + kind);
        }
        return usage.get();
    }

    public int limit(GenerationBudgetKind kind) {
        return limits.limit(kind);
    }

    public int remaining(GenerationBudgetKind kind) {
        return Math.max(0, limit(kind) - used(kind));
    }

    public void cancel(String reason) {
        cancellationReason.compareAndSet(null, normalizeReason(reason, "cancelled"));
        cancelled.set(true);
    }

    public void complete(String status) {
        terminalStatus.compareAndSet(null, normalizeReason(status, "completed"));
    }

    public GenerationExecutionSnapshot snapshot() {
        EnumMap<GenerationBudgetKind, Integer> usageSnapshot = new EnumMap<>(GenerationBudgetKind.class);
        EnumMap<GenerationBudgetKind, Integer> limitSnapshot = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            usageSnapshot.put(kind, used(kind));
            limitSnapshot.put(kind, limit(kind));
        }
        return new GenerationExecutionSnapshot(
                taskId,
                appId,
                userId,
                startedAt,
                deadlineAt,
                isCancelled(),
                cancellationReason(),
                terminalStatus.get(),
                Map.copyOf(usageSnapshot),
                Map.copyOf(limitSnapshot)
        );
    }

    private String normalizeReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
