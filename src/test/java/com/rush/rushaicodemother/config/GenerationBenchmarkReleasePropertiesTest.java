package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationBenchmarkReleasePropertiesTest {

    @Test
    void applicationYamlMustBindEveryFirstPreviewReleaseLimit() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        GenerationBenchmarkReleaseProperties properties = Binder.get(environment)
                .bind("app.generation-benchmark.release-gate",
                        Bindable.of(GenerationBenchmarkReleaseProperties.class))
                .orElseThrow(() -> new AssertionError("生成质量评测发布门禁配置未绑定"));

        assertEquals(1.0, properties.getMinimumFirstPreviewObservationRate());
        assertEquals(Duration.ofSeconds(60), maximum(properties, GenerationMode.CREATE));
        assertEquals(Duration.ofSeconds(90), maximum(properties, GenerationMode.LIGHT_EDIT));
        assertEquals(Duration.ofMinutes(3), maximum(properties, GenerationMode.AGENT_EDIT));
        assertEquals(Duration.ofMinutes(5), maximum(properties, GenerationMode.HEAVY_EXPERT));
        assertEquals(Duration.ofSeconds(30), properties.getMaximumP99FirstTokenLatency());
        assertEquals(Duration.ofMinutes(5), properties.getMaximumP99FirstPreviewLatency());
        assertTrue(properties.isDurationConfigurationValid());
    }

    @Test
    void incompleteModeLimitsMustBeRejected() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMaximumP90FirstPreviewLatencyByMode(Map.of(
                GenerationMode.CREATE, Duration.ofSeconds(60)
        ));

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void firstPreviewEvidenceCoverageMustRemainComplete() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMinimumFirstPreviewObservationRate(0.99);

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void p99FirstTokenLimitMustNotBeLowerThanP90Limit() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMaximumP99FirstTokenLatency(Duration.ofSeconds(14));

        assertFalse(properties.isDurationConfigurationValid());
    }

    private Duration maximum(GenerationBenchmarkReleaseProperties properties,
                             GenerationMode mode) {
        return properties.getMaximumP90FirstPreviewLatencyByMode().get(mode);
    }
}
