package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiPromptCatalogPropertiesTest {

    @Test
    void canaryPercentageMustRequireCanaryVersion() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();
        AiPromptCatalogProperties.Release release = new AiPromptCatalogProperties.Release();
        release.setCanaryPercentage(10);
        properties.getReleases().put("codegen-vue-project", release);

        assertFalse(properties.isConfigurationValid());

        release.setCanaryVersion("v2");
        assertTrue(properties.isConfigurationValid());
    }

    @Test
    void runtimeRefreshIntervalMustBePositiveAndBounded() {
        AiPromptCatalogProperties properties = new AiPromptCatalogProperties();

        properties.getRuntimeReleases().setRefreshInterval(Duration.ZERO);
        assertFalse(properties.isConfigurationValid());

        properties.getRuntimeReleases().setRefreshInterval(Duration.ofMinutes(6));
        assertFalse(properties.isConfigurationValid());

        properties.getRuntimeReleases().setRefreshInterval(Duration.ofSeconds(5));
        assertTrue(properties.isConfigurationValid());
    }
}
