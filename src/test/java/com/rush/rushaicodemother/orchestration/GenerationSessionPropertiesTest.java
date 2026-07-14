package com.rush.rushaicodemother.orchestration;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationSessionPropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void defaultsMustBePositiveAndProductionBounded() {
        GenerationSessionProperties properties = new GenerationSessionProperties();

        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.getLockStripes()).isEqualTo(64);
        assertThat(properties.getMaxTrackedSessions()).isEqualTo(1_000);
        assertThat(properties.getCompletedReplayRetention()).isEqualTo(Duration.ofSeconds(30));
        assertThat(properties.getCleanupInterval()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void invalidResourceBoundsMustBeRejected() {
        GenerationSessionProperties properties = new GenerationSessionProperties();
        properties.setLockStripes(0);
        properties.setMaxTrackedSessions(0);
        properties.setCompletedReplayRetention(Duration.ZERO);
        properties.setCleanupInterval(Duration.ZERO);

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void cleanupIntervalMustNotExceedReplayRetention() {
        GenerationSessionProperties properties = new GenerationSessionProperties();
        properties.setCompletedReplayRetention(Duration.ofSeconds(10));
        properties.setCleanupInterval(Duration.ofSeconds(11));

        assertThat(validator.validate(properties)).isNotEmpty();
    }

    @Test
    void replayRetentionMustRemainWithinOneHour() {
        GenerationSessionProperties properties = new GenerationSessionProperties();
        properties.setCompletedReplayRetention(Duration.ofHours(2));

        assertThat(validator.validate(properties)).isNotEmpty();
    }
}