package com.rush.rushaicodemother.orchestration.dag;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTaskSnapshotPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustBeProductionBounded() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();

        assertTrue(validator.validate(properties).isEmpty());
        assertTrue(properties.getMaxSnapshotBytes() > 0);
        assertTrue(properties.getMaxSnapshotsPerApp() > 0);
        assertTrue(properties.getRetention().isPositive());
        assertFalse(properties.isReplaySafeStartCheckpointElisionEnabled());
        assertFalse(properties.isReplaySafeCompletionCheckpointCoalescingEnabled());
        assertTrue(properties.getReplaySafeCompletionCheckpointInterval() >= 2);
    }

    @Test
    void invalidStorageBoundsMustBeRejected() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setRootDirectory(Path.of(""));
        properties.setMaxSnapshotBytes(1024);
        properties.setMaxSnapshotsPerApp(0);
        properties.setRetention(Duration.ZERO);
        properties.setLockStripes(0);
        properties.setReplaySafeCompletionCheckpointInterval(1);

        assertFalse(validator.validate(properties).isEmpty());
    }

    @Test
    void completionCheckpointCoalescingMustRequireStartCheckpointElision() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setReplaySafeCompletionCheckpointCoalescingEnabled(true);

        assertFalse(validator.validate(properties).isEmpty());

        properties.setReplaySafeStartCheckpointElisionEnabled(true);
        assertTrue(validator.validate(properties).isEmpty());
    }
}
