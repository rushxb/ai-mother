package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 用于生成代码隔离的低基数操作指标。 */
@Component
public class GeneratedCodeSandboxMetricsCollector {

    private static final Set<String> WORKLOADS = Set.of(
            "project-command", "dependency-process", "git-command", "dev-server"
    );

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> executionCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> executionTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> cleanupCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> readinessCounters = new ConcurrentHashMap<>();

    @Autowired
    public GeneratedCodeSandboxMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private GeneratedCodeSandboxMetricsCollector() {
        this.meterRegistry = null;
    }

    public static GeneratedCodeSandboxMetricsCollector noOp() {
        return new GeneratedCodeSandboxMetricsCollector();
    }

    public void recordExecution(
            String backend,
            String workload,
            String status,
            Duration duration
    ) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedBackend = backend(backend);
        String normalizedWorkload = workload(workload);
        String normalizedStatus = normalize(status);
        String key = String.join(":", normalizedBackend, normalizedWorkload, normalizedStatus);
        executionCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generated_code_sandbox_executions_total")
                .description("Generated-code sandbox executions")
                .tag("backend", normalizedBackend)
                .tag("workload", normalizedWorkload)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
        executionTimers.computeIfAbsent(key, unused -> Timer.builder(
                        "generated_code_sandbox_execution_duration_seconds")
                .description("Generated-code sandbox execution duration")
                .tag("backend", normalizedBackend)
                .tag("workload", normalizedWorkload)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    public void recordCleanup(String backend, String status) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedBackend = backend(backend);
        String normalizedStatus = normalize(status);
        String key = normalizedBackend + ":" + normalizedStatus;
        cleanupCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generated_code_sandbox_cleanup_total")
                .description("Generated-code sandbox cleanup outcomes")
                .tag("backend", normalizedBackend)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
    }

    public void recordReadiness(String resource, String status) {
        if (meterRegistry == null) {
            return;
        }
        String normalizedResource = normalize(resource);
        String normalizedStatus = normalize(status);
        String key = normalizedResource + ":" + normalizedStatus;
        readinessCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generated_code_sandbox_readiness_total")
                .description("Generated-code sandbox startup readiness outcomes")
                .tag("resource", normalizedResource)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
    }

    private String backend(String value) {
        String normalized = normalize(value);
        return "container".equals(normalized) || "host-local".equals(normalized)
                ? normalized
                : "unknown";
    }

    private String workload(String value) {
        String normalized = normalize(value);
        return WORKLOADS.contains(normalized) ? normalized : "other";
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "_");
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
