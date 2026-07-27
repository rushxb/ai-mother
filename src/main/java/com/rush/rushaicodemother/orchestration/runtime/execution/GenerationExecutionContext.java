package com.rush.rushaicodemother.orchestration.runtime.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 用于一代任务的线程安全控制平面。
 *
 * <p>上下文被显式传递到异步边界，而不是依赖于
 * ThreadLocal，因为生成工作在请求线程、Reactor 回调和
 * 虚拟线程.</p>
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
    private final AtomicInteger successfulWorkspaceMutations;
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
                limits, Map.of(), 0, clock);
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
                                        int restoredSuccessfulWorkspaceMutations,
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
        if (restoredSuccessfulWorkspaceMutations < 0) {
            throw new IllegalArgumentException("恢复的成功工作区变更数不能小于 0");
        }
        this.successfulWorkspaceMutations = new AtomicInteger(restoredSuccessfulWorkspaceMutations);
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
                limits, snapshot.usages(), snapshot.successfulWorkspaceMutations(), clock);
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
     * 将最新的持久工人时代与当地环境联系起来。
     *
     * <p>Approval 继续合法地推进纪元，同时保留相同的上下文。
     * 重新绑定旧的或冲突的围栏被拒绝。</p>
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
     * 返回活动任务是否仍有足够的挂钟时间来启动有界阶段。
     *
     * <p> 故意与 {@link #clampTimeout(Duration)} 分开。夹紧保护
     * 已经被允许的操作，而此方法可以防止启动多步
     * 无法为所需后续工作和终结留出足够时间的阶段。</p>
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
     * 将操作超时限制为剩余任务期限。
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
     * 为首预览前的可选操作计算软截止时间，并保护后续确定性完成窗口。
     *
     * <p>质量增强操作没有足够时间时返回空值，不把首预览软截止误报为整个任务失败。
     * 首预览已经发布后，操作重新受任务总 deadline 约束。</p>
     */
    public Optional<Duration> optionalFirstPreviewOperationTimeout(Duration requestedTimeout) {
        assertCanContinue();
        if (requestedTimeout == null || requestedTimeout.isZero() || requestedTimeout.isNegative()) {
            throw new IllegalArgumentException("操作超时必须大于 0");
        }
        if (firstPreviewReadyAt.get() != null) {
            return Optional.of(clampTimeout(requestedTimeout));
        }
        Instant previewOperationDeadline = firstPreviewDeadlineAt
                .minus(limits.firstPreviewCompletionReserve());
        Instant effectiveDeadline = previewOperationDeadline.isBefore(deadlineAt)
                ? previewOperationDeadline
                : deadlineAt;
        Duration remaining = Duration.between(clock.instant(), effectiveDeadline);
        if (remaining.compareTo(limits.minimumOperationTimeout()) < 0) {
            return Optional.empty();
        }
        return Optional.of(requestedTimeout.compareTo(remaining) <= 0
                ? requestedTimeout
                : remaining);
    }

    /**
     * 在操作开始之前自动保留一个单位。
     */
    public int consume(GenerationBudgetKind kind) {
        return consume(kind, 1);
    }

    /**
     * 在操作开始前原子预留多个预算单位，失败时不会产生部分扣减。
     */
    public int consume(GenerationBudgetKind kind, int units) {
        Objects.requireNonNull(kind, "kind");
        if (units <= 0) {
            throw new IllegalArgumentException("预算预留数量必须大于 0");
        }
        assertCanContinue();
        AtomicInteger usage = usages.get(kind);
        int limit = limits.limit(kind);
        while (true) {
            int current = usage.get();
            if (units > limit - current) {
                throw new GenerationBudgetExceededException(kind, limit);
            }
            int updated = current + units;
            if (usage.compareAndSet(current, updated)) {
                return updated;
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

    /** 在补丁确定落盘后记录成功工作区变更，返回累计成功操作数。 */
    public int recordSuccessfulWorkspaceMutations(int operationCount) {
        if (operationCount <= 0) {
            throw new IllegalArgumentException("成功工作区变更数必须大于 0");
        }
        return successfulWorkspaceMutations.addAndGet(operationCount);
    }

    public int successfulWorkspaceMutationCount() {
        return successfulWorkspaceMutations.get();
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
                successfulWorkspaceMutationCount(),
                Map.copyOf(usageSnapshot),
                Map.copyOf(limitSnapshot)
        );
    }

    private String normalizeReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
