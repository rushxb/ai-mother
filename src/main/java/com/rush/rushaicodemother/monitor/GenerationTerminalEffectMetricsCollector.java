package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalEffectBacklog;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 终态副作用 outbox 的低基数运行与积压遥测。 */
@Component
public class GenerationTerminalEffectMetricsCollector {

    private static final Set<String> OUTCOMES = Set.of(
            "claimed", "completed", "retry_scheduled", "dead_letter",
            "lease_lost", "processing_error", "operation_completed", "replayed");
    private static final Set<String> STATUSES = Set.of("success", "error");

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();
    private final Map<String, Timer> timers = new ConcurrentHashMap<>();
    private final BacklogState backlogState = new BacklogState();

    @Autowired
    public GenerationTerminalEffectMetricsCollector(MeterRegistry registry) {
        this.registry = registry;
        gauge("generation_terminal_outbox_pending", "Retryable terminal effects", backlogState.pending);
        gauge("generation_terminal_outbox_retrying", "Delayed terminal effect retries", backlogState.retrying);
        gauge("generation_terminal_outbox_leased", "Leased terminal effects", backlogState.leased);
        gauge("generation_terminal_outbox_dead_letter", "Exhausted terminal effects", backlogState.deadLetter);
        gauge("generation_terminal_outbox_oldest_age_seconds",
                "Age of the oldest retryable terminal effect", backlogState.oldestAgeSeconds);
    }

    private GenerationTerminalEffectMetricsCollector() {
        this.registry = null;
    }

    public static GenerationTerminalEffectMetricsCollector noOp() {
        return new GenerationTerminalEffectMetricsCollector();
    }

    public void recordItems(String outcome, long count) {
        if (registry == null || count <= 0) {
            return;
        }
        String boundedOutcome = bounded(outcome, OUTCOMES);
        counters.computeIfAbsent("item:" + boundedOutcome, unused -> Counter.builder(
                        "generation_terminal_outbox_items_total")
                .description("Terminal effect outbox item outcomes")
                .tag("outcome", boundedOutcome)
                .register(registry)).increment(count);
    }

    public void recordBatch(String status, Duration duration) {
        if (registry == null) {
            return;
        }
        String boundedStatus = bounded(status, STATUSES);
        String key = "batch:" + boundedStatus;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "generation_terminal_outbox_batches_total")
                .description("Terminal effect outbox batch outcomes")
                .tag("status", boundedStatus)
                .register(registry)).increment();
        timers.computeIfAbsent(key, unused -> Timer.builder(
                        "generation_terminal_outbox_batch_duration_seconds")
                .description("Terminal effect outbox batch latency")
                .tag("status", boundedStatus)
                .publishPercentileHistogram()
                .register(registry)).record(nonNegative(duration));
    }

    public void recordBacklogRefresh(String status) {
        if (registry == null) {
            return;
        }
        String boundedStatus = bounded(status, STATUSES);
        counters.computeIfAbsent("backlog:" + boundedStatus, unused -> Counter.builder(
                        "generation_terminal_outbox_backlog_refresh_total")
                .description("Terminal effect backlog refresh outcomes")
                .tag("status", boundedStatus)
                .register(registry)).increment();
    }

    public void updateBacklog(GenerationTerminalEffectBacklog backlog, Instant observedAt) {
        if (registry == null || backlog == null || observedAt == null) {
            return;
        }
        backlogState.pending.set(backlog.pending());
        backlogState.retrying.set(backlog.retrying());
        backlogState.leased.set(backlog.leased());
        backlogState.deadLetter.set(backlog.deadLetter());
        Instant oldest = backlog.oldestPendingAt();
        backlogState.oldestAgeSeconds.set(oldest == null
                ? 0L : Math.max(0L, Duration.between(oldest, observedAt).toSeconds()));
    }

    private void gauge(String name, String description, AtomicLong value) {
        if (registry == null) {
            return;
        }
        Gauge.builder(name, value, AtomicLong::get)
                .description(description)
                .register(registry);
    }

    private String bounded(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : "unknown";
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }

    private static final class BacklogState {
        private final AtomicLong pending = new AtomicLong();
        private final AtomicLong retrying = new AtomicLong();
        private final AtomicLong leased = new AtomicLong();
        private final AtomicLong deadLetter = new AtomicLong();
        private final AtomicLong oldestAgeSeconds = new AtomicLong();
    }
}
