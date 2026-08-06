package com.rush.rushaicodemother.monitor.latency;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationDurationSamples;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationStageDurationSample;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 按路由构建分段延迟画像，为「准备阶段是否值得并行」提供证据。
 *
 * <p>复用 {@link GenerationDurationSampleRepository} 的有界样本查询与
 * {@link GenerationTaskProgressProperties} 的缓存参数，不新增表、不新增 SQL、不新增配置项、
 * 不新建线程池。</p>
 */
@Service
public class GenerationRouteLatencySegmentService {

    private static final String ROUTE_PATTERN = "[a-z0-9_-]{1,64}";

    /**
     * 允许据此做并行决策的最小成功任务样本数。
     *
     * <p>取值对应生成链路优化清单阶段一门禁「每路由 ≥ 100 个样本」。</p>
     */
    public static final int MINIMUM_TASK_SAMPLES_FOR_DECISION = 100;

    /** 允许据此做并行决策的最小分段归类完整率。 */
    public static final double MINIMUM_SAMPLE_COMPLETENESS_PERCENT = 95.0d;

    private final GenerationDurationSampleRepository sampleRepository;
    private final GenerationTaskProgressProperties properties;
    private final Clock clock;
    private final Cache<String, GenerationRouteLatencySegmentProfile> profileCache;

    @Autowired
    public GenerationRouteLatencySegmentService(GenerationDurationSampleRepository sampleRepository,
                                               GenerationTaskProgressProperties properties) {
        this(sampleRepository, properties, Clock.systemUTC());
    }

    GenerationRouteLatencySegmentService(GenerationDurationSampleRepository sampleRepository,
                                        GenerationTaskProgressProperties properties,
                                        Clock clock) {
        this.sampleRepository = Objects.requireNonNull(sampleRepository, "sampleRepository");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.profileCache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCachedRoutes())
                .expireAfterWrite(properties.getProfileCacheTtl())
                .build();
    }

    /**
     * 获取指定路由的分段画像。
     *
     * @param route 运行时路由
     * @return 分段延迟画像
     */
    public GenerationRouteLatencySegmentProfile getProfile(String route) {
        return profileCache.get(normalizeRoute(route), this::loadProfile);
    }

    /**
     * 使指定路由的缓存失效。
     *
     * @param route 运行时路由
     */
    public void invalidate(String route) {
        profileCache.invalidate(normalizeRoute(route));
    }

    /** 加载分段画像。 */
    private GenerationRouteLatencySegmentProfile loadProfile(String route) {
        GenerationDurationSamples samples = sampleRepository.loadRecentSuccessfulSamples(
                route, properties.getTaskSampleLimit(), properties.getSpanSampleLimit());
        long maximumDurationMs = properties.getMaximumEstimatedDuration().toMillis();

        List<Long> taskDurations = sanitizeDurations(samples.taskDurationsMs(), maximumDurationMs);
        long taskTotalP90 = percentile(taskDurations, 0.90d);

        Map<GenerationLatencySegment, List<Long>> durationsBySegment =
                new EnumMap<>(GenerationLatencySegment.class);
        int usableSpanCount = 0;
        int unmappedSpanCount = 0;
        for (GenerationStageDurationSample sample : samples.stageDurations()) {
            if (sample == null || sample.durationMs() <= 0 || sample.durationMs() > maximumDurationMs) {
                continue;
            }
            usableSpanCount++;
            Optional<GenerationLatencySegment> segment =
                    GenerationLatencySegment.fromCategoryName(sample.category());
            if (segment.isEmpty()) {
                // PIPELINE 父跨度与历史类别不计入分段，但要计入完整率分母以暴露归类缺口。
                unmappedSpanCount++;
                continue;
            }
            durationsBySegment.computeIfAbsent(segment.get(), ignored -> new ArrayList<>())
                    .add(sample.durationMs());
        }

        List<GenerationRouteLatencySegmentProfile.SegmentLatency> segments = durationsBySegment.entrySet()
                .stream()
                .map(entry -> toSegmentLatency(entry.getKey(), entry.getValue(), taskTotalP90))
                .sorted(Comparator.comparingLong(
                                GenerationRouteLatencySegmentProfile.SegmentLatency::p90DurationMs)
                        .reversed()
                        .thenComparing(GenerationRouteLatencySegmentProfile.SegmentLatency::segment))
                .toList();

        double completeness = completenessPercent(usableSpanCount, unmappedSpanCount);
        return new GenerationRouteLatencySegmentProfile(
                route,
                taskDurations.size(),
                usableSpanCount,
                unmappedSpanCount,
                completeness,
                percentile(taskDurations, 0.50d),
                taskTotalP90,
                percentile(taskDurations, 0.99d),
                taskDurations.size() >= MINIMUM_TASK_SAMPLES_FOR_DECISION
                        && completeness >= MINIMUM_SAMPLE_COMPLETENESS_PERCENT,
                segments,
                Instant.now(clock)
        );
    }

    private GenerationRouteLatencySegmentProfile.SegmentLatency toSegmentLatency(
            GenerationLatencySegment segment,
            List<Long> durations,
            long taskTotalP90Ms
    ) {
        durations.sort(Long::compareTo);
        long p90 = percentile(durations, 0.90d);
        return new GenerationRouteLatencySegmentProfile.SegmentLatency(
                segment,
                durations.size(),
                percentile(durations, 0.50d),
                p90,
                percentile(durations, 0.99d),
                sharePercent(p90, taskTotalP90Ms)
        );
    }

    /** 归类完整率；无可用 span 时视为 0，避免用空样本冒充完整。 */
    private double completenessPercent(int usableSpanCount, int unmappedSpanCount) {
        if (usableSpanCount <= 0) {
            return 0.0d;
        }
        int mapped = usableSpanCount - unmappedSpanCount;
        return round((double) mapped * 100.0d / usableSpanCount);
    }

    private double sharePercent(long segmentDurationMs, long taskTotalP90Ms) {
        if (segmentDurationMs <= 0 || taskTotalP90Ms <= 0) {
            return 0.0d;
        }
        return round((double) segmentDurationMs * 100.0d / taskTotalP90Ms);
    }

    private double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private List<Long> sanitizeDurations(List<Long> values, long maximumDurationMs) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> value > 0 && value <= maximumDurationMs)
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    /** 与 {@code GenerationDurationProfileService} 保持同一分位数语义。 */
    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    /** 规范化路由标识。 */
    private String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("生成路由不能为空");
        }
        String normalized = route.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(ROUTE_PATTERN)) {
            throw new IllegalArgumentException("生成路由格式不合法");
        }
        return normalized;
    }
}
