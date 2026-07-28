package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationDurationSamples;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationDurationSampleRepository.GenerationStageDurationSample;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 构建有界的、缓存的持续时间百分位，而不将无界遥测数据加载到内存中。 */
@Service
public class GenerationDurationProfileService {

    private static final String ROUTE_PATTERN = "[a-z0-9_-]{1,64}";

    private final GenerationDurationSampleRepository sampleRepository;
    private final GenerationTaskProgressProperties properties;
    private final Clock clock;
    private final Cache<String, GenerationDurationProfile> profileCache;

    @Autowired
    public GenerationDurationProfileService(GenerationDurationSampleRepository sampleRepository,
                                            GenerationTaskProgressProperties properties) {
        this(sampleRepository, properties, Clock.systemUTC());
    }

    GenerationDurationProfileService(GenerationDurationSampleRepository sampleRepository,
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

    public GenerationDurationProfile getProfile(String route) {
        String normalizedRoute = normalizeRoute(route);
        return profileCache.get(normalizedRoute, this::loadProfile);
    }

    /**
 * 处理{@code invalidate}。
 *
 * @param route 代理路由
 */
    public void invalidate(String route) {
        profileCache.invalidate(normalizeRoute(route));
    }

    /** 加载配置档。 */
    private GenerationDurationProfile loadProfile(String route) {
        GenerationDurationSamples samples = sampleRepository.loadRecentSuccessfulSamples(
                route, properties.getTaskSampleLimit(), properties.getSpanSampleLimit());
        long maximumDurationMs = properties.getMaximumEstimatedDuration().toMillis();
        List<Long> taskDurations = sanitizeDurations(samples.taskDurationsMs(), maximumDurationMs);
        List<GenerationStageDurationProfile> stages = buildStageProfiles(
                samples.stageDurations(), maximumDurationMs);
        return new GenerationDurationProfile(
                route,
                taskDurations.size(),
                percentile(taskDurations, 0.50d),
                percentile(taskDurations, 0.90d),
                taskDurations.isEmpty() ? 0L : taskDurations.getLast(),
                stages,
                Instant.now(clock)
        );
    }

    /** 构建并返回阶段{@code Profiles}。 */
    private List<GenerationStageDurationProfile> buildStageProfiles(
            List<GenerationStageDurationSample> samples,
            long maximumDurationMs
    ) {
        Map<StageKey, List<Long>> grouped = new HashMap<>();
        if (samples != null) {
            for (GenerationStageDurationSample sample : samples) {
                if (sample == null || sample.durationMs() <= 0 || sample.durationMs() > maximumDurationMs) {
                    continue;
                }
                String stage = normalizeDimension(sample.stage(), 96);
                String category = normalizeDimension(sample.category(), 32);
                if (stage == null || category == null) {
                    continue;
                }
                grouped.computeIfAbsent(new StageKey(stage, category), ignored -> new ArrayList<>())
                        .add(sample.durationMs());
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> toStageProfile(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingLong(GenerationStageDurationProfile::p90DurationMs).reversed()
                        .thenComparing(GenerationStageDurationProfile::stage)
                        .thenComparing(GenerationStageDurationProfile::category))
                .limit(properties.getMaxStageProfiles())
                .toList();
    }

    private GenerationStageDurationProfile toStageProfile(StageKey key, List<Long> values) {
        values.sort(Long::compareTo);
        return new GenerationStageDurationProfile(
                key.stage(), key.category(), values.size(),
                percentile(values, 0.50d), percentile(values, 0.90d), values.getLast());
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

    private long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    /** 规范化{@code Route}。 */
    private String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            throw new IllegalArgumentException("route cannot be blank");
        }
        String normalized = route.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches(ROUTE_PATTERN)) {
            throw new IllegalArgumentException("route format is invalid");
        }
        return normalized;
    }

    private String normalizeDimension(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private record StageKey(String stage, String category) {
    }
}
