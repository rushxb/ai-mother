package com.rush.rushaicodemother.monitor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 生成共享事件流的低基数指标收集器。 */
@Component
public class GenerationEventStreamMetricsCollector {

    private static final Set<String> APPEND_KINDS = Set.of("event", "complete");
    private static final Set<String> OUTCOMES = Set.of("success", "failed");
    private static final Set<String> DELTA_DISPOSITIONS = Set.of(
            "immediate", "buffered", "oversized", "capacity_bypass", "shutdown_bypass"
    );
    private static final Set<String> FLUSH_TRIGGERS = Set.of(
            "size", "window", "retry", "barrier", "complete", "shutdown", "bypass"
    );

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, Counter> appendCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Timer> appendTimers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> deltaInputCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Counter> deltaFlushCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> deltaEventsPerFlush = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> deltaCharsPerFlush = new ConcurrentHashMap<>();

    public GenerationEventStreamMetricsCollector(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
 * 记录 Redis{@code Append}相关指标或状态。
 *
 * @param kind 类别
 * @param outcome 结果
 * @param duration 目标时长
 */
    public void recordRedisAppend(String kind, String outcome, Duration duration) {
        String normalizedKind = allowed(kind, APPEND_KINDS, "unknown");
        String normalizedOutcome = allowed(outcome, OUTCOMES, "failed");
        String key = normalizedKind + ":" + normalizedOutcome;
        appendCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_event_stream_redis_appends_total")
                        .description("Redis 生成事件流原子追加次数")
                        .tag("kind", normalizedKind)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).increment();
        appendTimers.computeIfAbsent(key, unused ->
                Timer.builder("generation_event_stream_redis_append_duration_seconds")
                        .description("Redis 生成事件流原子追加耗时")
                        .tag("kind", normalizedKind)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(nonNegative(duration));
    }

    /**
 * 记录增量输入相关指标或状态。
 *
 * @param disposition {@code disposition} 对应的调用参数
 */
    public void recordDeltaInput(String disposition) {
        String normalizedDisposition = allowed(disposition, DELTA_DISPOSITIONS, "unknown");
        deltaInputCounters.computeIfAbsent(normalizedDisposition, unused ->
                Counter.builder("generation_event_stream_delta_inputs_total")
                        .description("共享事件流接收的 AI 增量事件数")
                        .tag("disposition", normalizedDisposition)
                        .register(meterRegistry)
        ).increment();
    }

    /**
 * 记录增量{@code Flush}相关指标或状态。
 *
 * @param trigger {@code trigger} 对应的调用参数
 * @param outcome 结果
 * @param eventCount 事件数量
 * @param charCount {@code charCount} 对应的调用参数
 */
    public void recordDeltaFlush(String trigger,
                                 String outcome,
                                 int eventCount,
                                 int charCount) {
        String normalizedTrigger = allowed(trigger, FLUSH_TRIGGERS, "unknown");
        String normalizedOutcome = allowed(outcome, OUTCOMES, "failed");
        String key = normalizedTrigger + ":" + normalizedOutcome;
        deltaFlushCounters.computeIfAbsent(key, unused ->
                Counter.builder("generation_event_stream_delta_flushes_total")
                        .description("共享事件流 AI 增量缓冲冲刷次数")
                        .tag("trigger", normalizedTrigger)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).increment();
        deltaEventsPerFlush.computeIfAbsent(key, unused ->
                DistributionSummary.builder("generation_event_stream_delta_events_per_flush")
                        .description("单次共享事件流冲刷合并的 AI 增量事件数")
                        .tag("trigger", normalizedTrigger)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(Math.max(0, eventCount));
        deltaCharsPerFlush.computeIfAbsent(key, unused ->
                DistributionSummary.builder("generation_event_stream_delta_chars_per_flush")
                        .description("单次共享事件流冲刷包含的 AI 增量字符数")
                        .tag("trigger", normalizedTrigger)
                        .tag("outcome", normalizedOutcome)
                        .register(meterRegistry)
        ).record(Math.max(0, charCount));
    }

    private Duration nonNegative(Duration duration) {
        return duration == null || duration.isNegative() ? Duration.ZERO : duration;
    }

    private String allowed(String value, Set<String> allowed, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : fallback;
    }
}
