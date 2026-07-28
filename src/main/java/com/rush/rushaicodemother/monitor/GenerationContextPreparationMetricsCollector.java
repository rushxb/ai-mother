package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 生成上下文PreparationMetrics指标采集器。
 */
@Component
@RequiredArgsConstructor
public class GenerationContextPreparationMetricsCollector {

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> readCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> readTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> overlapCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> overlapTimers = new ConcurrentHashMap<>();

    /**
 * 记录记忆{@code Read}相关指标或状态。
 *
 * @param source 来源数据
 * @param mode 模式
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordMemoryRead(String source, String mode, String status, Duration duration) {
        String normalizedSource = normalize(source);
        String normalizedMode = normalize(mode);
        String normalizedStatus = normalize(status);
        String key = String.join(":", normalizedSource, normalizedMode, normalizedStatus);
        readCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generation_memory_context_reads_total")
                .description("生成记忆上下文读取次数")
                .tag("source", normalizedSource)
                .tag("mode", normalizedMode)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
        readTimers.computeIfAbsent(key, unused -> Timer.builder(
                        "generation_memory_context_read_duration_seconds")
                .description("生成记忆上下文单项读取耗时")
                .tag("source", normalizedSource)
                .tag("mode", normalizedMode)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    /**
 * 记录记忆{@code Preparation}{@code Overlap}相关指标或状态。
 *
 * @param phase {@code phase} 对应的调用参数
 * @param status 目标状态
 * @param duration 目标时长
 */
    public void recordMemoryPreparationOverlap(String phase, String status, Duration duration) {
        String normalizedPhase = normalize(phase);
        String normalizedStatus = normalize(status);
        String key = normalizedPhase + ":" + normalizedStatus;
        overlapCounters.computeIfAbsent(key, unused -> Counter.builder(
                        "generation_memory_context_preparation_overlap_total")
                .description("生成记忆上下文与编排准备重叠执行次数")
                .tag("phase", normalizedPhase)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).increment();
        overlapTimers.computeIfAbsent(key, unused -> Timer.builder(
                        "generation_memory_context_preparation_overlap_duration_seconds")
                .description("生成记忆上下文与编排准备重叠执行耗时")
                .tag("phase", normalizedPhase)
                .tag("status", normalizedStatus)
                .register(meterRegistry)).record(nonNegative(duration));
    }

    private Duration nonNegative(Duration duration) {
        if (duration == null || duration.isNegative()) {
            return Duration.ZERO;
        }
        return duration;
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }
}
