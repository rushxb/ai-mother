package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 记录任务提交前路由遥测的有界等待与后台加载状态。 */
@Component
public class GenerationRoutingTelemetryMetricsCollector {

    private static final Set<String> SNAPSHOT_STATUSES = Set.of(
            "cache", "loaded", "timeout", "saturated", "unavailable", "interrupted", "failed");
    private static final Set<String> LOAD_STATUSES = Set.of("success", "failed", "saturated");

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    @Autowired
    public GenerationRoutingTelemetryMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private GenerationRoutingTelemetryMetricsCollector() {
        this.meterRegistry = null;
    }

    public static GenerationRoutingTelemetryMetricsCollector noOp() {
        return new GenerationRoutingTelemetryMetricsCollector();
    }

    public void recordSnapshot(String status, Duration duration) {
        record("snapshot", bounded(status, SNAPSHOT_STATUSES), duration);
    }

    public void recordLoad(String status, Duration duration) {
        record("load", bounded(status, LOAD_STATUSES), duration);
    }

    private void record(String phase, String status, Duration duration) {
        if (meterRegistry == null) {
            return;
        }
        String key = phase + ":" + status;
        counters.computeIfAbsent(key, ignored -> Counter.builder(
                        "generation_routing_telemetry_operations_total")
                .description("生成路由遥测操作次数")
                .tag("phase", phase)
                .tag("status", status)
                .register(meterRegistry)).increment();
        timers.computeIfAbsent(key, ignored -> Timer.builder(
                        "generation_routing_telemetry_operation_duration_seconds")
                .description("生成路由遥测操作耗时")
                .tag("phase", phase)
                .tag("status", status)
                .publishPercentileHistogram()
                .register(meterRegistry)).record(nonNegative(duration));
    }

    private String bounded(String value, Set<String> allowed) {
        return value != null && allowed.contains(value) ? value : "failed";
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
