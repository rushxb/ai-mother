package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 生成 SSE 的固定配置属性。
 */
@Data
@Component
@Validated
public class GenerationSseProperties {

    public static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(15);

    /** 心跳间隔。 */
    @NotNull
    private Duration heartbeatInterval = HEARTBEAT_INTERVAL;

    @AssertTrue(message = "生成 SSE 心跳间隔必须大于 0")
    public boolean isHeartbeatIntervalPositive() {
        return heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative();
    }
}
