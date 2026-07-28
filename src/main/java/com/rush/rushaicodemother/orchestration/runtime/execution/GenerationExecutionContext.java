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
import java.util.concurrent.atomic.AtomicLong;
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
    private final AtomicLong agentAttemptEpoch;
    private final AtomicInteger agentToolRoundLimit;
    private final AtomicInteger agentModelTurnsStarted;
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicReference<String> cancellationReason = new AtomicReference<>();
    private final AtomicReference<String> terminalStatus = new AtomicReference<>();
    private final AtomicReference<Instant> firstPreviewReadyAt = new AtomicReference<>();
    private final AtomicReference<GenerationExecutionFence> executionFence = new AtomicReference<>();

    /**
 * 创建生成执行上下文实例并完成必要的依赖和初始状态设置。
 *
 * @param taskId 任务编号
 * @param appId 应用编号
 * @param userId 用户编号
 * @param startedAt {@code startedAt} 对应的调用参数
 * @param limits 限制
 * @param clock 业务时钟
 */
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
                limits, Map.of(), 0, 0L, 0, 0, clock);
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId 不能为空");
        }
    }

    /** 创建生成执行上下文实例并完成必要的依赖和初始状态设置。 */
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
                                        long restoredAgentAttemptEpoch,
                                        int restoredAgentToolRoundLimit,
                                        int restoredAgentModelTurnsStarted,
                                        Clock clock) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
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
        validateRestoredAgentProgress(
                restoredAgentAttemptEpoch,
                restoredAgentToolRoundLimit,
                restoredAgentModelTurnsStarted
        );
        this.agentAttemptEpoch = new AtomicLong(restoredAgentAttemptEpoch);
        this.agentToolRoundLimit = new AtomicInteger(restoredAgentToolRoundLimit);
        this.agentModelTurnsStarted = new AtomicInteger(restoredAgentModelTurnsStarted);
        this.usages = new EnumMap<>(GenerationBudgetKind.class);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            int restored = restoredUsages == null ? 0 : restoredUsages.getOrDefault(kind, 0);
            if (restored < 0 || restored > limits.limit(kind)) {
                throw new IllegalArgumentException("restored generation budget usage is invalid: " + kind);
            }
            usages.put(kind, new AtomicInteger(restored));
        }
    }

    /**
 * 返回恢复。
 *
 * @param snapshot 快照
 * @param limits 限制
 * @param clock 业务时钟
 * @return 生成执行上下文
 */
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
                limits, snapshot.usages(), snapshot.successfulWorkspaceMutations(),
                snapshot.agentAttemptEpoch(), snapshot.agentToolRoundLimit(),
                snapshot.agentModelTurnsStarted(), clock);
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

    /**
 * 更新{@code First}预览就绪的标记状态。
 *
 * @return {@code First}预览就绪
 */
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

    /** 断言{@code Can}{@code Continue}仍满足当前执行约束。 */
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

    /**
 * 返回{@code remaining}时长。
 *
 * @return 生成执行上下文
 */
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

    /**
 * 返回{@code used}。
 *
 * @param kind 类别
 * @return 计算或处理后的数值结果
 */
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

    /**
 * 返回{@code remaining}。
 *
 * @param kind 类别
 * @return 计算或处理后的数值结果
 */
    public int remaining(GenerationBudgetKind kind) {
        return Math.max(0, limit(kind) - used(kind));
    }

    /** 为新的根模型尝试创建独立的 Agent 回合账本。 */
    public synchronized long beginAgentAttempt(int toolRoundLimit) {
        assertCanContinue();
        requirePositiveToolRoundLimit(toolRoundLimit);
        long nextEpoch;
        try {
            nextEpoch = Math.addExact(agentAttemptEpoch.get(), 1L);
        } catch (ArithmeticException overflow) {
            throw new GenerationExecutionPolicyException("Agent 尝试纪元已超出可表示范围");
        }
        agentAttemptEpoch.set(nextEpoch);
        agentToolRoundLimit.set(toolRoundLimit);
        agentModelTurnsStarted.set(0);
        return nextEpoch;
    }

    /**
     * 恢复审批 checkpoint 中的 Agent 回合进度。
     * 旧 checkpoint 至少根据已校验会话恢复当前待处理工具回合。
     */
    public synchronized void restoreAgentAttempt(int toolRoundLimit,
                                                 int minimumModelTurnsStarted) {
        assertCanContinue();
        requirePositiveToolRoundLimit(toolRoundLimit);
        if (minimumModelTurnsStarted < 0 || minimumModelTurnsStarted > toolRoundLimit) {
            throw new IllegalArgumentException("恢复的 Agent 模型回合数无效");
        }
        if (agentAttemptEpoch.get() == 0L) {
            agentAttemptEpoch.set(1L);
            agentToolRoundLimit.set(toolRoundLimit);
            agentModelTurnsStarted.set(minimumModelTurnsStarted);
            return;
        }
        if (agentToolRoundLimit.get() != toolRoundLimit) {
            throw new GenerationExecutionPolicyException("审批恢复的 Agent 工具回合上限不一致");
        }
        agentModelTurnsStarted.updateAndGet(current ->
                Math.max(current, minimumModelTurnsStarted));
    }

    /** 预留下一次 Agent 模型回合，并返回本次尝试内从 1 开始的序号。 */
    public synchronized int reserveAgentModelTurn(int expectedToolRoundLimit) {
        assertCanContinue();
        requirePositiveToolRoundLimit(expectedToolRoundLimit);
        if (agentAttemptEpoch.get() == 0L
                || agentToolRoundLimit.get() != expectedToolRoundLimit) {
            throw new GenerationExecutionPolicyException("Agent 模型回合尚未绑定到当前尝试");
        }
        int maximumModelTurns = Math.addExact(expectedToolRoundLimit, 1);
        int current = agentModelTurnsStarted.get();
        if (current >= maximumModelTurns) {
            throw new GenerationExecutionPolicyException("Agent 模型回合预算已耗尽");
        }
        int reserved = current + 1;
        agentModelTurnsStarted.set(reserved);
        return reserved;
    }

    /** 拒绝模型在最终无工具收口回合继续触发副作用。 */
    public synchronized void assertAgentToolExecutionAllowed() {
        int started = agentModelTurnsStarted.get();
        int toolLimit = agentToolRoundLimit.get();
        if (agentAttemptEpoch.get() == 0L || toolLimit <= 0 || started <= 0) {
            throw new GenerationExecutionPolicyException("Agent 工具调用缺少有效回合账本");
        }
        if (started > toolLimit) {
            throw new GenerationExecutionPolicyException("Agent 工具回合预算已耗尽");
        }
    }

    public long agentAttemptEpoch() {
        return agentAttemptEpoch.get();
    }

    public int agentToolRoundLimit() {
        return agentToolRoundLimit.get();
    }

    public int agentModelTurnsStarted() {
        return agentModelTurnsStarted.get();
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

    /**
 * 取消生成执行上下文。
 *
 * @param reason 原因
 */
    public void cancel(String reason) {
        cancellationReason.compareAndSet(null, normalizeReason(reason, "cancelled"));
        cancelled.set(true);
    }

    /**
 * 完成生成执行上下文并持久化终态。
 *
 * @param status 目标状态
 */
    public void complete(String status) {
        terminalStatus.compareAndSet(null, normalizeReason(status, "completed"));
    }

    /**
 * 返回快照。
 *
 * @return 生成执行上下文
 */
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
                agentAttemptEpoch(),
                agentToolRoundLimit(),
                agentModelTurnsStarted(),
                Map.copyOf(usageSnapshot),
                Map.copyOf(limitSnapshot)
        );
    }

    /** 校验{@code ate}{@code Restored}智能体{@code Progress}是否有效。 */
    private static void validateRestoredAgentProgress(long attemptEpoch,
                                                       int toolRoundLimit,
                                                       int modelTurnsStarted) {
        if (attemptEpoch < 0 || toolRoundLimit < 0 || modelTurnsStarted < 0) {
            throw new IllegalArgumentException("恢复的 Agent 回合账本不能包含负数");
        }
        if (attemptEpoch == 0L && (toolRoundLimit != 0 || modelTurnsStarted != 0)) {
            throw new IllegalArgumentException("恢复的 Agent 回合账本缺少尝试纪元");
        }
        if (attemptEpoch > 0L
                && (toolRoundLimit <= 0
                || modelTurnsStarted > Math.addExact(toolRoundLimit, 1))) {
            throw new IllegalArgumentException("恢复的 Agent 回合账本超出预算");
        }
    }

    private static void requirePositiveToolRoundLimit(int toolRoundLimit) {
        if (toolRoundLimit <= 0) {
            throw new IllegalArgumentException("Agent 工具回合上限必须大于 0");
        }
    }

    private String normalizeReason(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
