package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentProductivityPropertiesTest {

    @Test
    void defaultsMustUseHardcodedProductivityLimits() {
        AiAgentProductivityProperties properties = new AiAgentProductivityProperties();

        assertEquals(AiAgentProductivityProperties.MAXIMUM_TRACKED_TASKS,
                properties.getMaximumTrackedTasks());
        assertEquals(AiAgentProductivityProperties.RETENTION, properties.getRetention());
        assertEquals(AiAgentProductivityProperties.MAX_READ_ONLY_CALLS_WITHOUT_MUTATION,
                properties.getMaxReadOnlyCallsWithoutMutation());
        assertEquals(AiAgentProductivityProperties.MAX_MODEL_TURNS_WITHOUT_MUTATION,
                properties.getMaxModelTurnsWithoutMutation());
        assertEquals(AiAgentProductivityProperties.FORCED_ACTION_TURNS_BEFORE_FINALIZE,
                properties.getForcedActionTurnsBeforeFinalize());
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void retentionMustBePositive() {
        AiAgentProductivityProperties properties = new AiAgentProductivityProperties();
        properties.setRetention(Duration.ZERO);

        assertFalse(properties.isConfigurationValid());
    }
}
