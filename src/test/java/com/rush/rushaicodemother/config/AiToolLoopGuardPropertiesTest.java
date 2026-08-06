package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiToolLoopGuardPropertiesTest {

    @Test
    void defaultsMustUseHardcodedLoopLimits() {
        AiToolLoopGuardProperties properties = new AiToolLoopGuardProperties();

        assertEquals(AiToolLoopGuardProperties.MAXIMUM_TRACKED_TASKS,
                properties.getMaximumTrackedTasks());
        assertEquals(AiToolLoopGuardProperties.RETENTION, properties.getRetention());
        assertEquals(AiToolLoopGuardProperties.MAX_IDENTICAL_CALLS,
                properties.getMaxIdenticalCalls());
        assertEquals(AiToolLoopGuardProperties.MAX_NO_PROGRESS_CALLS,
                properties.getMaxNoProgressCalls());
        assertEquals(AiToolLoopGuardProperties.HISTORY_SIZE, properties.getHistorySize());
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void historyWindowMustCoverConfiguredDetectionThresholds() {
        AiToolLoopGuardProperties properties = new AiToolLoopGuardProperties();
        properties.setHistorySize(4);
        properties.setMaxNoProgressCalls(6);

        assertFalse(properties.isConfigurationValid());
    }
}
