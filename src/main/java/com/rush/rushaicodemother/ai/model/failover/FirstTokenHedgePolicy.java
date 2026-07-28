package com.rush.rushaicodemother.ai.model.failover;

import com.rush.rushaicodemother.core.handler.GenerationCancellationHandle;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 首 Token 对冲的不可变运行策略。每次逻辑调用最多启动一个影子请求。 */
public record FirstTokenHedgePolicy(
        boolean enabled,
        Duration delay,
        boolean requireDistinctProvider,
        FirstTokenHedgeScheduler scheduler
) {

    /** 创建{@code First}令牌{@code Hedge}策略实例并完成必要的依赖和初始状态设置。 */
    public FirstTokenHedgePolicy {
        if (enabled) {
            Objects.requireNonNull(delay, "首 Token 对冲延迟不能为空");
            Objects.requireNonNull(scheduler, "首 Token 对冲调度器不能为空");
            if (delay.isZero() || delay.isNegative()) {
                throw new IllegalArgumentException("首 Token 对冲延迟必须大于 0");
            }
        }
    }

    public static FirstTokenHedgePolicy disabled() {
        return new FirstTokenHedgePolicy(false, Duration.ZERO, true, null);
    }

    /** 判断当前状态是否允许{@code Hedge}。 */
    boolean canHedge(
            List<AiModelCandidate<dev.langchain4j.model.chat.StreamingChatModel>> candidates,
            List<Integer> candidateOrder
    ) {
        if (!enabled || candidates == null || candidateOrder == null || candidateOrder.size() < 2) {
            return false;
        }
        if (!requireDistinctProvider) {
            return true;
        }
        AiModelCandidate<?> primary = candidates.get(candidateOrder.get(0));
        AiModelCandidate<?> shadow = candidates.get(candidateOrder.get(1));
        return !primary.provider().equalsIgnoreCase(shadow.provider());
    }

    GenerationCancellationHandle schedule(Runnable task) {
        if (!enabled) {
            throw new IllegalStateException("未启用首 Token 对冲策略");
        }
        return scheduler.schedule(delay, task);
    }
}
