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
    }

    @Test
    void invalidStorageBoundsMustBeRejected() {
        GenerationTaskSnapshotProperties properties = new GenerationTaskSnapshotProperties();
        properties.setRootDirectory(Path.of(""));
        properties.setMaxSnapshotBytes(1024);
        properties.setMaxSnapshotsPerApp(0);
        properties.setRetention(Duration.ZERO);
        properties.setLockStripes(0);

        assertFalse(validator.validate(properties).isEmpty());
    }
}
