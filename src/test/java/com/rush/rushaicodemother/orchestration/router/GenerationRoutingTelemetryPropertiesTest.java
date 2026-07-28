package com.rush.rushaicodemother.orchestration.router;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationRoutingTelemetryPropertiesTest {

    @Test
    void defaultsMustKeepColdWaitBelowRefreshAndRetainStaleSnapshots() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();

        assertTrue(properties.isDurationConfigurationValid());
        assertTrue(properties.getColdLoadTimeout().compareTo(properties.getCacheTtl()) < 0);
        assertTrue(properties.getStaleRetention().compareTo(properties.getCacheTtl()) > 0);
    }

    @Test
    void coldWaitMustBeShorterThanRefreshInterval() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        properties.setColdLoadTimeout(Duration.ofSeconds(30));

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void staleRetentionMustExceedRefreshInterval() {
        GenerationRoutingTelemetryProperties properties = new GenerationRoutingTelemetryProperties();
        properties.setStaleRetention(Duration.ofSeconds(30));

        assertFalse(properties.isDurationConfigurationValid());
    }
}
