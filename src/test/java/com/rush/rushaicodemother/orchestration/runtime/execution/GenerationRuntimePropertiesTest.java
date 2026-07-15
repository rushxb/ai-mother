package com.rush.rushaicodemother.orchestration.runtime.execution;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRuntimePropertiesTest {

    @Test
    void defaultsProduceCompleteImmutableLimits() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();

        GenerationExecutionLimits limits = properties.toLimits();

        assertEquals(Duration.ofMinutes(20), limits.taskTimeout());
        assertEquals(Duration.ofMinutes(8), limits.modelCallTimeout());
        assertEquals(3, limits.limit(GenerationBudgetKind.MODEL_ATTEMPT));
        assertEquals(80, limits.limit(GenerationBudgetKind.TOOL_WRITE));
        assertEquals(4, limits.limit(GenerationBudgetKind.BUILD_EXECUTION));
        assertEquals(3, limits.limit(GenerationBudgetKind.REPAIR_ROUND));
        assertThrows(UnsupportedOperationException.class,
                () -> limits.budgets().put(GenerationBudgetKind.REPAIR_ROUND, 99));
    }

    @Test
    void invalidDurationRelationshipsAreRejectedBeforeRuntimeStart() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setTaskTimeout(Duration.ofSeconds(5));
        properties.setModelCallTimeout(Duration.ofSeconds(6));
        assertFalse(properties.isDurationConfigurationValid());
        assertThrows(IllegalArgumentException.class, properties::toLimits);

        properties.setTaskTimeout(Duration.ofSeconds(10));
        properties.setModelCallTimeout(Duration.ofSeconds(5));
        properties.setMinimumOperationTimeout(Duration.ofMillis(500));
        assertTrue(properties.isDurationConfigurationValid());
    }
}
