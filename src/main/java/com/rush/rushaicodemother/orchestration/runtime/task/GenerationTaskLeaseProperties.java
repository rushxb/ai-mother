package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** 工作线程租赁、心跳和孤儿恢复控制。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-lease")
public class GenerationTaskLeaseProperties {

    public static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);
    public static final Duration RECOVERY_SCAN_INTERVAL = Duration.ofSeconds(15);
    public static final int RECOVERY_BATCH_SIZE = 50;

    /** 可选的 stable 前缀；始终附加进程唯一的后缀。 */
    private String ownerId;

    private Duration leaseDuration = LEASE_DURATION;

    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    private Duration recoveryScanInterval = RECOVERY_SCAN_INTERVAL;

    @Min(1)
    @Max(500)
    private int recoveryBatchSize = RECOVERY_BATCH_SIZE;

    @AssertTrue(message = "generation task lease durations must be positive and heartbeat must be shorter than lease")
    public boolean isDurationConfigurationValid() {
        return isPositive(leaseDuration)
                && isPositive(heartbeatInterval)
                && isPositive(recoveryScanInterval)
                && heartbeatInterval.compareTo(leaseDuration) < 0;
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
