package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
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
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/** 低基数 Milvus 和持久的语义记忆发件箱遥测。 */
@Component
public class SemanticMemoryMetricsCollector {

    private static final Set<String> STORE_OPERATIONS = Set.of("upsert", "search", "delete");
    private static final Set<String> OUTBOXES = Set.of("generation", "deletion");
    private static final Set<String> STATUSES = Set.of("success", "error");
    private static final Set<String> READINESS_STATUSES = Set.of("ready", "failure");
    private static final Set<String> OUTBOX_OUTCOMES = Set.of(
            "claimed", "completed", "retry_scheduled", "dead_letter", "lease_lost");

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();
    private final Map<String, BacklogState> backlogStates = new ConcurrentHashMap<>();

    @Autowired
    public SemanticMemoryMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
        for (String outbox : OUTBOXES) {
            BacklogState state = new BacklogState();
            backlogStates.put(outbox, state);
            gauge("semantic_memory_outbox_pending", "Unresolved semantic-memory outbox items",
                    outbox, state, state.pending);
            gauge("semantic_memory_outbox_retrying", "Delayed semantic-memory outbox retries",
                    outbox, state, state.retrying);
            gauge("semantic_memory_outbox_leased", "Currently leased semantic-memory outbox items",
                    outbox, state, state.leased);
            gauge("semantic_memory_outbox_dead_letter", "Exhausted semantic-memory outbox items",
                    outbox, state, state.deadLetter);
            gauge("semantic_memory_outbox_oldest_age_seconds",
                    "Age of the oldest unresolved semantic-memory outbox item",
                    outbox, state, state.oldestAgeSeconds);
        }
    }

    private SemanticMemoryMetricsCollector() {
        this.meterRegistry = null;
    }

    public static SemanticMemoryMetricsCollector noOp() {
        return new SemanticMemoryMetricsCollector();
    }

    public void recordStoreOperation(String operation, String status, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOperation = bounded(operation, STORE_OPERATIONS);
        String boundedStatus = bounded(status, STATUSES);
        String key = "store:" + boundedOperation + ":" + boundedStatus;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "semantic_memory_store_operations_total")
                .description("Milvus semantic-memory store operation outcomes")
                .tag("operation", boundedOperation)
                .tag("status", boundedStatus)
                .register(meterRegistry)).increment();
        timers.computeIfAbsent(key, unused -> Timer.builder(
                        "semantic_memory_store_operation_duration_seconds")
                .description("Milvus semantic-memory store operation latency")
                .tag("operation", boundedOperation)
                .tag("status", boundedStatus)
                .publishPercentileHistogram()
                .register(meterRegistry)).record(nonNegative(duration));
    }

    public void recordMalformedRows(int count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        counters.computeIfAbsent("malformed_rows", unused -> Counter.builder(
                        "semantic_memory_malformed_rows_total")
                .description("Malformed Milvus rows skipped during semantic-memory recall")
                .register(meterRegistry)).increment(count);
    }

    public void recordReadiness(String status, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        String boundedStatus = bounded(status, READINESS_STATUSES);
        String key = "readiness:" + boundedStatus;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "semantic_memory_readiness_checks_total")
                .description("Milvus semantic-memory schema and index readiness outcomes")
                .tag("status", boundedStatus)
                .register(meterRegistry)).increment();
        timers.computeIfAbsent(key, unused -> Timer.builder(
                        "semantic_memory_readiness_duration_seconds")
                .description("Milvus semantic-memory schema and index readiness latency")
                .tag("status", boundedStatus)
                .publishPercentileHistogram()
                .register(meterRegistry)).record(nonNegative(duration));
    }

    public void recordFailover(String operation) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOperation = bounded(operation, STORE_OPERATIONS);
        counters.computeIfAbsent("failover:" + boundedOperation, unused -> Counter.builder(
                        "semantic_memory_failovers_total")
                .description("Semantic-memory operations that used or retained the local fallback")
                .tag("operation", boundedOperation)
                .register(meterRegistry)).increment();
    }

    public void recordOutboxItems(String outbox, String outcome, long count) {
        if (meterRegistry == null || count <= 0) {
            return;
        }
        String boundedOutbox = bounded(outbox, OUTBOXES);
        String boundedOutcome = bounded(outcome, OUTBOX_OUTCOMES);
        String key = "outbox_item:" + boundedOutbox + ":" + boundedOutcome;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "semantic_memory_outbox_items_total")
                .description("Semantic-memory durable outbox item outcomes")
                .tag("outbox", boundedOutbox)
                .tag("outcome", boundedOutcome)
                .register(meterRegistry)).increment(count);
    }

    public void recordOutboxBatch(String outbox, String status, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOutbox = bounded(outbox, OUTBOXES);
        String boundedStatus = bounded(status, STATUSES);
        String key = "outbox_batch:" + boundedOutbox + ":" + boundedStatus;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "semantic_memory_outbox_batches_total")
                .description("Semantic-memory outbox batch outcomes")
                .tag("outbox", boundedOutbox)
                .tag("status", boundedStatus)
                .register(meterRegistry)).increment();
        timers.computeIfAbsent(key, unused -> Timer.builder(
                        "semantic_memory_outbox_batch_duration_seconds")
                .description("Semantic-memory outbox batch processing latency")
                .tag("outbox", boundedOutbox)
                .tag("status", boundedStatus)
                .publishPercentileHistogram()
                .register(meterRegistry)).record(nonNegative(duration));
    }

    public void recordBacklogRefresh(String outbox, String status) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOutbox = bounded(outbox, OUTBOXES);
        String boundedStatus = bounded(status, STATUSES);
        String key = "backlog_refresh:" + boundedOutbox + ":" + boundedStatus;
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "semantic_memory_outbox_backlog_refresh_total")
                .description("Semantic-memory outbox backlog snapshot outcomes")
                .tag("outbox", boundedOutbox)
                .tag("status", boundedStatus)
                .register(meterRegistry)).increment();
    }

    public void updateBacklog(String outbox,
                              SemanticMemoryOutboxBacklog backlog,
                              Instant observedAt) {
        if (meterRegistry == null) {
            return;
        }
        String boundedOutbox = bounded(outbox, OUTBOXES);
        BacklogState state = backlogStates.get(boundedOutbox);
        if (state == null || backlog == null || observedAt == null) {
            return;
        }
        state.pending.set(backlog.pending());
        state.retrying.set(backlog.retrying());
        state.leased.set(backlog.leased());
        state.deadLetter.set(backlog.deadLetter());
        Instant oldest = backlog.oldestPendingAt();
        long oldestAge = oldest == null ? 0
                : Math.max(0, Duration.between(oldest, observedAt).toSeconds());
        state.oldestAgeSeconds.set(oldestAge);
    }

    private void gauge(String name,
                       String description,
                       String outbox,
                       BacklogState state,
                       AtomicLong value) {
        Gauge.builder(name, state, unused -> value.get())
                .description(description)
                .tag("outbox", outbox)
                .register(meterRegistry);
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
