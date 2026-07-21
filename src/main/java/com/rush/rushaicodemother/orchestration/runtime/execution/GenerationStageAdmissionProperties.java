package com.rush.rushaicodemother.orchestration.runtime.execution;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.stream.Stream;

/** Minimum remaining-time windows used before expensive generation stages are admitted. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-stage-admission")
public class GenerationStageAdmissionProperties {

    /** Minimum useful model window for an optional automatic-repair round. */
    private Duration repairModelMinimum = Duration.ofSeconds(60);

    /** Minimum useful window for dependency preparation and project build validation. */
    private Duration buildMinimum = Duration.ofSeconds(45);

    /** Minimum useful window for Dev Server startup and delayed error collection. */
    private Duration runtimeValidationMinimum = Duration.ofSeconds(15);

    /** Time protected for publication, lifecycle persistence, charging and terminal events. */
    private Duration terminalizationReserve = Duration.ofSeconds(10);

    @AssertTrue(message = "generation stage admission durations must all be greater than zero")
    public boolean isConfigurationValid() {
        return Stream.of(
                        repairModelMinimum,
                        buildMinimum,
                        runtimeValidationMinimum,
                        terminalizationReserve
                )
                .allMatch(value -> value != null && !value.isZero() && !value.isNegative());
    }
}
