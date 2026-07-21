package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-sse")
public class GenerationSseProperties {

    @NotNull
    private Duration heartbeatInterval = Duration.ofSeconds(15);

    @AssertTrue(message = "generation SSE heartbeat interval must be positive")
    public boolean isHeartbeatIntervalPositive() {
        return heartbeatInterval != null && !heartbeatInterval.isZero() && !heartbeatInterval.isNegative();
    }
}
