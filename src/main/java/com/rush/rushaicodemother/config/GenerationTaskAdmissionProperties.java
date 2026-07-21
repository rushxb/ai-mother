package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** User-level noisy-neighbour guard until an organization tenant identity is introduced. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-task-admission")
public class GenerationTaskAdmissionProperties {

    @Min(1)
    @Max(100)
    private int maxNonTerminalTasksPerUser = 4;
}
