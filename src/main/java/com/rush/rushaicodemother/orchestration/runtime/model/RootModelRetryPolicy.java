package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/** 为根模型重试计算受任务预算和截止时间约束的退避计划。 */
public final class RootModelRetryPolicy {

    private final Duration minimumDelay;
    private final Duration maximumDelay;
    private final double jitter;
    private final DoubleSupplier randomSource;

    public RootModelRetryPolicy(AiModelRuntimeProperties properties) {
        this(properties, () -> ThreadLocalRandom.current().nextDouble());
    }

    /** 创建根模型重试策略实例并完成必要的依赖和初始状态设置。 */
    RootModelRetryPolicy(AiModelRuntimeProperties properties, DoubleSupplier randomSource) {
        Objects.requireNonNull(properties, "AI 模型运行配置不能为空");
        this.minimumDelay = requirePositive(
                properties.getRootModelRetryMinDelay(), "根模型重试最小延迟必须大于 0");
        this.maximumDelay = requirePositive(
                properties.getRootModelRetryMaxDelay(), "根模型重试最大延迟必须大于 0");
        if (maximumDelay.compareTo(minimumDelay) < 0) {
            throw new IllegalArgumentException("根模型重试最大延迟不能小于最小延迟");
        }
        this.jitter = properties.getRootModelRetryJitter();
        if (!Double.isFinite(jitter) || jitter < 0 || jitter > 1) {
            throw new IllegalArgumentException("根模型重试抖动比例必须在 0 到 1 之间");
        }
        this.randomSource = Objects.requireNonNull(randomSource, "根模型重试随机源不能为空");
    }

    /**
 * 根据输入信号确定根模型重试策略。
 *
 * @param retryIndex 重试索引
 * @param executionContext 执行上下文
 * @return 根模型重试策略
 */
    public Decision decide(long retryIndex, GenerationExecutionContext executionContext) {
        if (retryIndex < 0) {
            throw new IllegalArgumentException("根模型重试序号不能小于 0");
        }
        if (executionContext != null
                && !executionContext.hasRemainingBudget(GenerationBudgetKind.ROOT_MODEL_ATTEMPT)) {
            return Decision.rejected(Rejection.BUDGET_EXHAUSTED);
        }

        Duration delay = jittered(exponentialDelay(retryIndex));
        if (executionContext == null) {
            return Decision.retryAfter(delay);
        }

        Duration operationReserve = executionContext.limits().minimumOperationTimeout();
        Duration affordableDelay = executionContext.remainingDuration().minus(operationReserve);
        if (affordableDelay.compareTo(minimumDelay) < 0) {
            return Decision.rejected(Rejection.DEADLINE_EXHAUSTED);
        }
        return Decision.retryAfter(min(delay, affordableDelay));
    }

    /** 返回{@code exponential}延迟。 */
    private Duration exponentialDelay(long retryIndex) {
        Duration delay = minimumDelay;
        for (long index = 0; index < retryIndex && delay.compareTo(maximumDelay) < 0; index++) {
            if (delay.compareTo(maximumDelay.dividedBy(2)) > 0) {
                return maximumDelay;
            }
            delay = min(delay.multipliedBy(2), maximumDelay);
        }
        return delay;
    }

    /** 返回{@code jittered}。 */
    private Duration jittered(Duration baseDelay) {
        if (jitter == 0) {
            return baseDelay;
        }
        double sample = randomSource.getAsDouble();
        if (!Double.isFinite(sample)) {
            sample = 0.5;
        }
        sample = Math.max(0, Math.min(Math.nextDown(1.0), sample));
        double factor = 1 + ((sample * 2) - 1) * jitter;
        long jitteredNanos = Math.max(1L, Math.round(baseDelay.toNanos() * factor));
        Duration candidate = Duration.ofNanos(jitteredNanos);
        if (candidate.compareTo(minimumDelay) < 0) {
            return minimumDelay;
        }
        return min(candidate, maximumDelay);
    }

    private Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    public enum Rejection {
        NONE,
        BUDGET_EXHAUSTED,
        DEADLINE_EXHAUSTED
    }

    public record Decision(boolean retryAllowed, Duration delay, Rejection rejection) {

        private static Decision retryAfter(Duration delay) {
            return new Decision(true, delay, Rejection.NONE);
        }

        private static Decision rejected(Rejection rejection) {
            return new Decision(false, Duration.ZERO, rejection);
        }

        /** 创建决策实例并完成必要的依赖和初始状态设置。 */
        public Decision {
            Objects.requireNonNull(delay, "根模型重试延迟不能为空");
            Objects.requireNonNull(rejection, "根模型重试拒绝原因不能为空");
            if (retryAllowed && (delay.isZero() || delay.isNegative())) {
                throw new IllegalArgumentException("允许重试时延迟必须大于 0");
            }
            if (retryAllowed != (rejection == Rejection.NONE)) {
                throw new IllegalArgumentException("根模型重试决策状态不一致");
            }
        }
    }
}
