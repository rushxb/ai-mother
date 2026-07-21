package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Cluster-wide model admission limits applied before every provider request. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-capacity")
public class AiModelCapacityProperties {

    private boolean enabled;

    /** Redis key prefix; model identity is hashed before being appended. */
    @NotBlank
    private String keyPrefix = "ai:model:capacity:";

    @Min(1)
    @Max(1000)
    private int maxConcurrentPerModel = 4;

    @Min(1)
    @Max(1_000_000)
    private long requestsPerMinute = 120;

    @Min(1)
    @Max(100_000_000)
    private long tokensPerMinute = 500_000;

    /** Bounds output-token reservation so one request cannot monopolize the whole TPM window. */
    @Min(1)
    @Max(1_000_000)
    private int maxReservedOutputTokens = 16_384;

    /** Total wait allowed at each admission gate before failover is attempted. */
    private Duration acquireTimeout = Duration.ofMillis(250);

    /** Short Redis permit lease; active calls renew it until their bounded hold deadline. */
    private Duration permitLease = Duration.ofSeconds(60);

    /** Shared-scheduler heartbeat interval; must leave enough retry headroom before expiry. */
    private Duration heartbeatInterval = Duration.ofSeconds(20);

    /** Absolute safety ceiling when a caller cannot provide a narrower upstream timeout. */
    private Duration maximumHold = Duration.ofMinutes(16);

    /** Grace added to the concrete provider timeout before heartbeat renewal is stopped. */
    private Duration maximumHoldGrace = Duration.ofSeconds(30);

    /** Bounded application-wide scheduler pool; never creates one thread per request. */
    @Min(1)
    @Max(16)
    private int schedulerThreads = 2;

    /** Idle Redis admission keys are deleted automatically. */
    private Duration idleTtl = Duration.ofHours(2);

    /** Availability escape hatch; production should remain fail-closed. */
    private boolean failOpen;

    @AssertTrue(message = "AI model capacity duration configuration is invalid")
    public boolean isDurationConfigurationValid() {
        return atLeastOneMillisecond(acquireTimeout)
                && atLeastOneMillisecond(permitLease)
                && atLeastOneMillisecond(heartbeatInterval)
                && atLeastOneMillisecond(maximumHold)
                && atLeastOneMillisecond(maximumHoldGrace)
                && atLeastOneMillisecond(idleTtl)
                && heartbeatInterval.compareTo(permitLease.dividedBy(2)) <= 0
                && maximumHold.compareTo(permitLease) > 0
                && idleTtl.compareTo(maximumHold) > 0;
    }

    private boolean atLeastOneMillisecond(Duration value) {
        return value != null && !value.isNegative() && value.toMillis() >= 1L;
    }
}
