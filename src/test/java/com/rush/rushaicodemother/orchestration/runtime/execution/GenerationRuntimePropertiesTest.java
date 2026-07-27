package com.rush.rushaicodemother.orchestration.runtime.execution;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRuntimePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsProduceCompleteImmutableLimits() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();

        GenerationExecutionLimits limits = properties.toLimits();

        assertEquals(Duration.ofMinutes(10), limits.taskTimeout());
        assertEquals(Duration.ofMinutes(4), limits.modelCallTimeout());
        assertEquals(Duration.ofSeconds(10), limits.firstPreviewCompletionReserve());
        assertEquals(Duration.ofSeconds(5), properties.getStreamSnapshotUpdateInterval());
        assertEquals(20_000, properties.getStreamSnapshotMaxChars());
        assertEquals(3, limits.limit(GenerationBudgetKind.ROOT_MODEL_ATTEMPT));
        assertEquals(16, limits.limit(GenerationBudgetKind.MODEL_TURN));
        assertEquals(4, limits.limit(GenerationBudgetKind.PROVIDER_FAILOVER_ATTEMPT));
        assertEquals(80, limits.limit(GenerationBudgetKind.TOOL_WRITE));
        assertEquals(2, limits.limit(GenerationBudgetKind.BUILD_EXECUTION));
        assertEquals(1, limits.limit(GenerationBudgetKind.REPAIR_ROUND));
        assertThrows(UnsupportedOperationException.class,
                () -> limits.budgets().put(GenerationBudgetKind.REPAIR_ROUND, 99));
        assertTrue(validator.validate(properties).isEmpty());
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
        properties.setFirstPreviewCompletionReserve(Duration.ofSeconds(1));
        assertTrue(properties.isDurationConfigurationValid());

        properties.setFirstPreviewCompletionReserve(Duration.ofMillis(9_501));
        assertFalse(properties.isDurationConfigurationValid());
        assertThrows(IllegalArgumentException.class, properties::toLimits);
    }

    @Test
    void streamSnapshotConfigurationMustRemainBounded() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();

        properties.setStreamSnapshotUpdateInterval(Duration.ofMillis(99));
        assertFalse(properties.isDurationConfigurationValid());

        properties.setStreamSnapshotUpdateInterval(Duration.ofMillis(100));
        assertTrue(properties.isDurationConfigurationValid());

        properties.setStreamSnapshotUpdateInterval(Duration.ofSeconds(61));
        assertFalse(properties.isDurationConfigurationValid());

        properties.setStreamSnapshotUpdateInterval(Duration.ofMinutes(1));
        properties.setStreamSnapshotMaxChars(0);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setStreamSnapshotMaxChars(100_001);
        assertFalse(validator.validate(properties).isEmpty());

        properties.setStreamSnapshotMaxChars(100_000);
        assertTrue(validator.validate(properties).isEmpty());
    }

    @Test
    void compatibilityBudgetMustCoverHeavyRoutingGenerationAndRepair() {
        GenerationRuntimeProperties properties = new GenerationRuntimeProperties();
        properties.setMaxRootModelAttempts(2);
        properties.setMaxRepairRounds(1);

        assertFalse(properties.isModelBudgetConfigurationValid());
        assertThrows(IllegalArgumentException.class, properties::toLimits);

        properties.setMaxRootModelAttempts(3);
        assertTrue(properties.isModelBudgetConfigurationValid());
    }

    @Test
    void legacyLimitsConstructorMustFitReserveIntoAPreviouslyValidNarrowWindow() {
        GenerationExecutionLimits defaults = new GenerationRuntimeProperties().toLimits();

        GenerationExecutionLimits limits = new GenerationExecutionLimits(
                Duration.ofSeconds(1),
                Duration.ofMillis(800),
                Duration.ofMillis(600),
                defaults.budgets()
        );

        assertEquals(Duration.ofMillis(400), limits.firstPreviewCompletionReserve());
    }
}
