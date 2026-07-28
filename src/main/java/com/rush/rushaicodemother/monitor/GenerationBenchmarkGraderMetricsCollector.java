package com.rush.rushaicodemother.monitor;

import com.rush.rushaicodemother.orchestration.benchmark.GenerationBenchmarkQualityDimension;
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

/** 用于确定性工作区和托管运行时基准评分器的低基数指标。 */
@Component
public class GenerationBenchmarkGraderMetricsCollector {

    private static final Set<String> KINDS = Set.of("workspace", "runtime");
    private static final Set<String> STATUSES = Set.of("passed", "failed", "error");

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> timers = new ConcurrentHashMap<>();

    @Autowired
    public GenerationBenchmarkGraderMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    private GenerationBenchmarkGraderMetricsCollector() {
        this.meterRegistry = null;
    }

    public static GenerationBenchmarkGraderMetricsCollector noOp() {
        return new GenerationBenchmarkGraderMetricsCollector();
    }

    /**
 * 记录生成基准测试{@code Grader}{@code Metrics}相关指标或状态。
 *
 * @param kind 类别
 * @param dimension {@code dimension} 对应的调用参数
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void record(
            String kind,
            GenerationBenchmarkQualityDimension dimension,
            String status,
            Duration duration
    ) {
        if (meterRegistry == null || dimension == null) {
            return;
        }
        String normalizedKind = bounded(kind, KINDS, "workspace");
        String normalizedStatus = bounded(status, STATUSES, "error");
        String normalizedDimension = dimension.name().toLowerCase(Locale.ROOT);
        String key = String.join(":", normalizedKind, normalizedDimension, normalizedStatus);
        counters.computeIfAbsent(key, unused -> Counter.builder(
                        "ai_generation_benchmark_grader_results_total")
                .description("AI generation benchmark grader outcomes")
                .tag("kind", normalizedKind)
                .tag("dimension", normalizedDimension)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
        timers.computeIfAbsent(key, unused -> Timer.builder(
                        "ai_generation_benchmark_grader_duration_seconds")
                .description("AI generation benchmark grader execution duration")
                .tag("kind", normalizedKind)
                .tag("dimension", normalizedDimension)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    private String bounded(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }
}
