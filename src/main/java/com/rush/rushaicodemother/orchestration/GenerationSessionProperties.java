package com.rush.rushaicodemother.orchestration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成会话注册表的固定并发与内存资源限制。
 */
@Data
@Component
@Validated
public class GenerationSessionProperties {

    public static final int LOCK_STRIPES = 64;
    public static final int MAX_TRACKED_SESSIONS = 1_000;
    public static final Duration COMPLETED_REPLAY_RETENTION = Duration.ofSeconds(30);
    public static final Duration CLEANUP_INTERVAL = Duration.ofSeconds(5);

    private static final Duration MAX_REPLAY_RETENTION = Duration.ofHours(1);

    /** 单实例用于串行化同一应用启动操作的固定条带锁数量。 */
    @Min(1)
    @Max(4_096)
    private int lockStripes = LOCK_STRIPES;

    /** 单实例允许同时跟踪的活动会话与短期回放会话总数。 */
    @Min(1)
    @Max(10_000)
    private int maxTrackedSessions = MAX_TRACKED_SESSIONS;

    /** 已完成轻量会话为 SSE 重连保留的时间。 */
    @NotNull
    private Duration completedReplayRetention = COMPLETED_REPLAY_RETENTION;

    /** 扫描并移除已过期回放会话的固定周期。 */
    @NotNull
    private Duration cleanupInterval = CLEANUP_INTERVAL;

    @AssertTrue(message = "生成会话回放保留时间和清理周期必须大于 0，且回放保留时间不能超过 1 小时")
    public boolean isDurationConfigurationValid() {
        return isPositive(completedReplayRetention)
                && completedReplayRetention.compareTo(MAX_REPLAY_RETENTION) <= 0
                && isPositive(cleanupInterval);
    }

    @AssertTrue(message = "生成会话清理周期不能大于回放保留时间")
    public boolean isCleanupIntervalSafe() {
        return completedReplayRetention != null
                && cleanupInterval != null
                && cleanupInterval.compareTo(completedReplayRetention) <= 0;
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}