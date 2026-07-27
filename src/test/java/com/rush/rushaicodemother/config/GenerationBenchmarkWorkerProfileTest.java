package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GenerationBenchmarkWorkerProfileTest {

    @Test
    void profileMustDisableOnlineRolesAndEnableCompleteGraders() throws Exception {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader().load(
                "benchmark-worker",
                new ClassPathResource("application-benchmark-worker.yml")
        );

        assertEquals("none", value(sources, "spring.main.web-application-type"));
        assertEquals(false, value(sources, "app.background-jobs.enabled"));
        assertEquals("local", value(sources, "app.generation-task-queue.transport"));
        assertEquals("local", value(sources, "app.generation-event-stream.transport"));
        assertEquals(false, value(sources, "app.template-pre-warm.enabled"));
        assertEquals(true, value(sources, "app.generation-benchmark.worker.enabled"));
        assertEquals(
                "${GENERATION_BENCHMARK_BROWSER_GRADING_ENABLED:true}",
                value(sources, "app.generation-benchmark.browser-grading.enabled")
        );
        assertEquals(
                "${GENERATION_BENCHMARK_BACKEND_GRADING_ENABLED:true}",
                value(sources, "app.generation-benchmark.backend-grading.enabled")
        );
    }

    private Object value(List<PropertySource<?>> sources, String key) {
        return sources.stream()
                .map(source -> source.getProperty(key))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }
}
