package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationModelTimeoutPolicyTest {

    @Test
    void shouldUseConfiguredFirstSignalTimeoutWhenTurnWindowIsLarger() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofSeconds(45));
        GenerationModelTimeoutPolicy policy = new GenerationModelTimeoutPolicy(properties);

        assertEquals(
                Duration.ofSeconds(45),
                policy.firstSignalTimeout(Duration.ofMinutes(2))
        );
    }

    @Test
    void shouldClampFirstSignalTimeoutToRemainingTurnWindow() {
        AiModelRuntimeProperties properties = new AiModelRuntimeProperties();
        properties.setFirstSignalTimeout(Duration.ofSeconds(45));
        GenerationModelTimeoutPolicy policy = new GenerationModelTimeoutPolicy(properties);

        assertEquals(
                Duration.ofSeconds(12),
                policy.firstSignalTimeout(Duration.ofSeconds(12))
        );
    }
}
