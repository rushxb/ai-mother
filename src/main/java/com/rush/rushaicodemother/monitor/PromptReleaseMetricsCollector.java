package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** Low-cardinality operational metrics for prompt release refresh and mutations. */
@Component
public class PromptReleaseMetricsCollector {

    private static final Set<String> REFRESH_STATUSES = Set.of(
            "activated", "unchanged", "failed", "disabled"
    );
    private static final Set<String> ACTIONS = Set.of("publish", "rollback");
    private static final Set<String> MUTATION_STATUSES = Set.of("success", "conflict", "failed");

    private final MeterRegistry meterRegistry;
    private final AtomicLong activeRevision = new AtomicLong();
    private final ConcurrentMap<String, Counter> refreshCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> refreshTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> mutationCounters = new ConcurrentHashMap<>();

    public PromptReleaseMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        Gauge.builder("ai_prompt_release_active_revision", activeRevision, AtomicLong::get)
                .description("Active durable AI prompt release revision")
                .register(meterRegistry);
    }

    private PromptReleaseMetricsCollector() {
        this.meterRegistry = null;
    }

    public static PromptReleaseMetricsCollector noOp() {
        return new PromptReleaseMetricsCollector();
    }

    public void recordRefresh(String status, Duration duration, long revision) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedStatus = allowed(status, REFRESH_STATUSES, "failed");
        activeRevision.set(Math.max(0L, revision));
        refreshCounters.computeIfAbsent(normalizedStatus, key -> Counter.builder(
                        "ai_prompt_release_refresh_total")
                .description("AI prompt release refresh outcomes")
                .tag("status", key)
                .register(meterRegistry)).increment();
        refreshTimers.computeIfAbsent(normalizedStatus, key -> Timer.builder(
                        "ai_prompt_release_refresh_duration_seconds")
                .description("AI prompt release refresh duration")
                .tag("status", key)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    public void recordMutation(String action, String status) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedAction = allowed(action, ACTIONS, "publish");
        String normalizedStatus = allowed(status, MUTATION_STATUSES, "failed");
        String key = normalizedAction + ":" + normalizedStatus;
        mutationCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "ai_prompt_release_mutations_total")
                .description("AI prompt release publish and rollback outcomes")
                .tag("action", normalizedAction)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
    }

    private String allowed(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
