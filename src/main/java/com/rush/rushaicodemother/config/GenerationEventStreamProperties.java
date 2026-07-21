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

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-event-stream")
public class GenerationEventStreamProperties {

    @NotBlank
    private String transport = "local";

    @NotBlank
    private String keyPrefix = "generation:events:";

    private Duration retention = Duration.ofHours(2);

    private Duration pollInterval = Duration.ofMillis(500);

    @Min(10)
    @Max(5000)
    private int maxEventsPerTask = 500;

    @Min(1)
    @Max(500)
    private int readBatchSize = 100;

    @Min(10)
    @Max(100000)
    private int maxTrackedTasks = 1000;

    @AssertTrue(message = "generation event stream durations must be positive")
    public boolean isDurationConfigurationValid() {
        return isPositive(retention) && isPositive(pollInterval);
    }

    private boolean isPositive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
