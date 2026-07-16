package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenerationDurationProfileServiceTest {

    @Test
    void mustBuildNearestRankPercentilesAndCachePerNormalizedRoute() {
        AtomicInteger loads = new AtomicInteger();
        GenerationDurationSampleRepository repository = (route, taskLimit, spanLimit) -> {
            loads.incrementAndGet();
            assertEquals("heavy", route);
            assertEquals(200, taskLimit);
            assertEquals(5_000, spanLimit);
            return new GenerationDurationSampleRepository.GenerationDurationSamples(
                    List.of(100L, 200L, 300L, 400L),
                    List.of(
                            new GenerationDurationSampleRepository.GenerationStageDurationSample(
                                    "build", "BUILD", 50L),
                            new GenerationDurationSampleRepository.GenerationStageDurationSample(
                                    "build", "BUILD", 150L),
                            new GenerationDurationSampleRepository.GenerationStageDurationSample(
                                    "llm_generation", "MODEL", 300L),
                            new GenerationDurationSampleRepository.GenerationStageDurationSample(
                                    "ignored", "MODEL", 9_000_000L)
                    )
            );
        };
        GenerationTaskProgressProperties properties = properties();
        properties.setMaximumEstimatedDuration(Duration.ofSeconds(10));
        Instant now = Instant.parse("2026-07-16T04:00:00Z");
        GenerationDurationProfileService service = new GenerationDurationProfileService(
                repository, properties, Clock.fixed(now, ZoneOffset.UTC));

        GenerationDurationProfile first = service.getProfile(" HEAVY ");
        GenerationDurationProfile second = service.getProfile("heavy");

        assertEquals(first, second);
        assertEquals(1, loads.get());
        assertEquals(4, first.taskSampleSize());
        assertEquals(200L, first.p50TotalDurationMs());
        assertEquals(400L, first.p90TotalDurationMs());
        assertEquals(400L, first.maxTotalDurationMs());
        assertEquals(now, first.computedAt());
        assertEquals(2, first.stages().size());
        GenerationStageDurationProfile build = first.stages().stream()
                .filter(stage -> stage.stage().equals("build"))
                .findFirst()
                .orElseThrow();
        assertEquals("build", build.category().toLowerCase());
        assertEquals(2, build.sampleSize());
        assertEquals(50L, build.p50DurationMs());
        assertEquals(150L, build.p90DurationMs());
    }

    @Test
    void invalidRouteMustFailBeforeRepositoryAccess() {
        GenerationDurationProfileService service = new GenerationDurationProfileService(
                (route, taskLimit, spanLimit) -> {
                    throw new AssertionError("repository must not be called");
                }, properties(), Clock.systemUTC());

        assertThrows(IllegalArgumentException.class, () -> service.getProfile("../secret"));
    }

    private GenerationTaskProgressProperties properties() {
        GenerationTaskProgressProperties properties = new GenerationTaskProgressProperties();
        properties.setProfileCacheTtl(Duration.ofMinutes(1));
        return properties;
    }
}
