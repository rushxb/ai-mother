package com.rush.rushaicodemother.orchestration.runtime.task;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Worker lease, heartbeat and orphan recovery controls. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-lease")
public class GenerationTaskLeaseProperties {

    /** Optional stable prefix; a process-unique suffix is always appended. */
    private String ownerId;

    private Duration leaseDuration = Duration.ofSeconds(30);

    private Duration heartbeatInterval = Duration.ofSeconds(10);

    private Duration recoveryScanInterval = Duration.ofSeconds(15);

    @Min(1)
    @Max(500)
    private int recoveryBatchSize = 50;

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
