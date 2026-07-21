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
    private final String slaProfile;
    private final Instant firstPreviewDeadlineAt;
    private final GenerationExecutionLimits limits;
    private final Clock clock;
    private final EnumMap<GenerationBudgetKind, AtomicInteger> usages;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> cancellationReason = new AtomicReference<>();
    private final AtomicReference<String> terminalStatus = new AtomicReference<>();
    private final AtomicReference<Instant> firstPreviewReadyAt = new AtomicReference<>();
    private final AtomicReference<GenerationExecutionFence> executionFence = new AtomicReference<>();

    public GenerationExecutionContext(
            String taskId,
            Long appId,
            Long userId,
            Instant startedAt,
            GenerationExecutionLimits limits,
            Clock clock
    ) {
        this(taskId, appId, userId, startedAt, defaultDeadline(startedAt, limits),
                "legacy-default", defaultDeadline(startedAt, limits), null,
                limits, Map.of(), clock);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
    }

    private GenerationExecutionContext(String taskId,
                                       Long appId,
                                       Long userId,
                                       Instant startedAt,
                                       Instant deadlineAt,
                                       String slaProfile,
                                       Instant firstPreviewDeadlineAt,
                                       Instant restoredFirstPreviewReadyAt,
                                       GenerationExecutionLimits limits,
                                       Map<GenerationBudgetKind, Integer> restoredUsages,
                                       Clock clock) {
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 涓嶈兘涓虹┖");
        }
        this.taskId = taskId;
        this.appId = appId;
        this.userId = userId;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt");
        this.deadlineAt = Objects.requireNonNull(deadlineAt, "deadlineAt");
        this.slaProfile = normalizeReason(slaProfile, "legacy-default");
        this.firstPreviewDeadlineAt = Objects.requireNonNull(firstPreviewDeadlineAt, "firstPreviewDeadlineAt");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (!deadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("deadlineAt must be after startedAt");
        }
        if (firstPreviewDeadlineAt.isAfter(deadlineAt) || !firstPreviewDeadlineAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("firstPreviewDeadlineAt must be within the task deadline");
        }
        if (restoredFirstPreviewReadyAt != null) {
            firstPreviewReadyAt.set(restoredFirstPreviewReadyAt);
        }
        this.usages = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            int restored = restoredUsages == null ? 0 : restoredUsages.getOrDefault(kind, 0);
            if (restored < 0 || restored > limits.limit(kind)) {
                throw new IllegalArgumentException("restored generation budget usage is invalid: " + kind);
            }
            usages.put(kind, new AtomicInteger(restored));
        }
    }

    public static GenerationExecutionContext restore(GenerationExecutionSnapshot snapshot,
                                                     GenerationExecutionLimits limits,
                                                     Clock clock) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.cancelled() || snapshot.terminalStatus() != null) {
            throw new IllegalArgumentException("only an active generation context can be restored");
        }
        return new GenerationExecutionContext(
                snapshot.taskId(), snapshot.appId(), snapshot.userId(),
                snapshot.startedAt(), snapshot.deadlineAt(), snapshot.slaProfile(),
                snapshot.firstPreviewDeadlineAt(), snapshot.firstPreviewReadyAt(),
                limits, snapshot.usages(), clock);
    }

    private static Instant defaultDeadline(Instant startedAt, GenerationExecutionLimits limits) {
        return Objects.requireNonNull(startedAt, "startedAt")
                .plus(Objects.requireNonNull(limits, "limits").taskTimeout());
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

    public String slaProfile() {
        return slaProfile;
    }

    public Instant firstPreviewDeadlineAt() {
        return firstPreviewDeadlineAt;
    }

    public Instant firstPreviewReadyAt() {
        return firstPreviewReadyAt.get();
    }

    public GenerationExecutionFence executionFence() {
        return executionFence.get();
    }

    /**
     * Binds the latest durable worker epoch to this local context.
     *
     * <p>Approval continuation legitimately advances the epoch while retaining the same context.
     * Rebinding an older or conflicting fence is rejected.</p>
     */
    public void bindExecutionFence(GenerationExecutionFence fence) {
        Objects.requireNonNull(fence, "fence");
        if (!taskId.equals(fence.taskId())) {
            throw new IllegalArgumentException("execution fence taskId does not match context taskId");
        }
        while (true) {
            GenerationExecutionFence current = executionFence.get();
            if (current == null) {
                if (executionFence.compareAndSet(null, fence)) {
                    return;
                }
                continue;
            }
            if (current.equals(fence)) {
                return;
            }
            if (fence.executionEpoch() <= current.executionEpoch()) {
                throw new GenerationExecutionPolicyException(
                        "generation execution fence cannot move backwards or change at the same epoch");
            }
            if (executionFence.compareAndSet(current, fence)) {
                return;
            }
        }
    }

    public GenerationFirstPreviewMilestone markFirstPreviewReady() {
        Instant now = clock.instant();
        boolean first = firstPreviewReadyAt.compareAndSet(null, now);
        Instant readyAt = first ? now : firstPreviewReadyAt.get();
        return new GenerationFirstPreviewMilestone(
                first,
                readyAt,
                firstPreviewDeadlineAt,
                Duration.between(startedAt, readyAt),
                readyAt.isAfter(firstPreviewDeadlineAt)
        );
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
     * Returns whether an active task still has enough wall-clock time to start a bounded stage.
     *
     * <p>This is intentionally separate from {@link #clampTimeout(Duration)}. Clamping protects an
     * operation that has already been admitted, while this method prevents starting a multi-step
     * stage that cannot leave enough time for required follow-up work and terminalization.</p>
     */
    public boolean hasRemainingTime(Duration minimumRequired) {
        if (minimumRequired == null || minimumRequired.isZero() || minimumRequired.isNegative()) {
            throw new IllegalArgumentException("minimumRequired must be greater than zero");
        }
        return !isCancelled()
                && !isCompleted()
                && !isDeadlineExceeded()
                && remainingDuration().compareTo(minimumRequired) >= 0;
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
                slaProfile,
                firstPreviewDeadlineAt,
                firstPreviewReadyAt(),
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
