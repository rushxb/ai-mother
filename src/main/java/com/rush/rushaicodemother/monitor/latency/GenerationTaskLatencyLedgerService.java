package com.rush.rushaicodemother.monitor.latency;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.monitor.span.GenerationSpanQueryService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 根据持久任务状态和已完成的跨度构建非重复计数的挂钟分类帐。 */
@Service
public class GenerationTaskLatencyLedgerService {

    private static final String PIPELINE_CATEGORY = "pipeline";

    private final DurableGenerationTaskRepository taskRepository;
    private final GenerationSpanQueryService spanQueryService;
    private final Clock clock;

    @Autowired
    public GenerationTaskLatencyLedgerService(DurableGenerationTaskRepository taskRepository,
                                              GenerationSpanQueryService spanQueryService) {
        this(taskRepository, spanQueryService, Clock.systemUTC());
    }

    GenerationTaskLatencyLedgerService(DurableGenerationTaskRepository taskRepository,
                                       GenerationSpanQueryService spanQueryService,
                                       Clock clock) {
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.spanQueryService = Objects.requireNonNull(spanQueryService, "spanQueryService");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
 * 获取并返回{@code Ledger}。
 *
 * @param taskId 任务编号
 * @return 生成任务{@code Latency}{@code Ledger}
 */
    public GenerationTaskLatencyLedger getLedger(String taskId) {
        DurableGenerationTaskRecord task = taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist"));
        List<GenerationSpanQueryService.StoredSpan> storedSpans = spanQueryService.findByTaskId(
                taskId, GenerationSpanQueryService.MAX_LIMIT);
        Instant calculatedAt = clock.instant();
        Instant effectiveEndAt = task.completedAt() == null ? calculatedAt : task.completedAt();
        long windowStartMs = task.submittedAt().toEpochMilli();
        long windowEndMs = Math.max(windowStartMs, effectiveEndAt.toEpochMilli());
        List<NormalizedSpan> spans = normalizeSpans(storedSpans, windowStartMs, windowEndMs);

        Map<String, List<Interval>> intervalsByCategory = new HashMap<>();
        Map<String, Integer> spanCounts = new HashMap<>();
        for (NormalizedSpan span : spans) {
            intervalsByCategory.computeIfAbsent(span.category(), ignored -> new ArrayList<>())
                    .add(span.interval());
            spanCounts.merge(span.category(), 1, Integer::sum);
        }

        Map<String, Long> inclusiveDurations = new HashMap<>();
        intervalsByCategory.forEach((category, intervals) ->
                inclusiveDurations.put(category, mergedDuration(intervals)));
        Map<String, Long> attributedCategoryDurations = attributedCategoryDurations(spans);
        long attributedLatencyMs = attributedCategoryDurations.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        long totalLatencyMs = Math.max(0, windowEndMs - windowStartMs);
        long unattributedLatencyMs = Math.max(0, totalLatencyMs - attributedLatencyMs);
        long inclusiveLatencyMs = inclusiveDurations.values().stream()
                .mapToLong(Long::longValue)
                .sum();
        long overlappingLatencyMs = Math.max(0, inclusiveLatencyMs - attributedLatencyMs);

        List<GenerationTaskLatencyLedger.CategoryLatency> categories = intervalsByCategory.keySet().stream()
                .map(category -> new GenerationTaskLatencyLedger.CategoryLatency(
                        category,
                        spanCounts.getOrDefault(category, 0),
                        attributedCategoryDurations.getOrDefault(category, 0L),
                        inclusiveDurations.getOrDefault(category, 0L),
                        percent(attributedCategoryDurations.getOrDefault(category, 0L), totalLatencyMs)
                ))
                .sorted(Comparator
                        .comparingLong(GenerationTaskLatencyLedger.CategoryLatency::attributedDurationMs)
                        .reversed()
                        .thenComparing(Comparator.comparingLong(
                                GenerationTaskLatencyLedger.CategoryLatency::inclusiveDurationMs).reversed())
                        .thenComparing(GenerationTaskLatencyLedger.CategoryLatency::category))
                .toList();
        String dominantCategory = categories.isEmpty()
                || categories.getFirst().attributedDurationMs() == 0
                ? "unattributed"
                : categories.getFirst().category();

        return new GenerationTaskLatencyLedger(
                task.taskId(),
                task.appId(),
                task.userId(),
                task.route(),
                task.status() == null ? "unknown" : task.status().getValue(),
                normalizeDimension(task.stage()),
                task.submittedAt(),
                task.deadlineAt(),
                task.completedAt(),
                calculatedAt,
                totalLatencyMs,
                attributedLatencyMs,
                unattributedLatencyMs,
                percent(attributedLatencyMs, totalLatencyMs),
                overlappingLatencyMs,
                deadlineOvershoot(task.deadlineAt(), effectiveEndAt),
                storedSpans == null ? 0 : storedSpans.size(),
                spans.size(),
                storedSpans != null && storedSpans.size() >= GenerationSpanQueryService.MAX_LIMIT,
                dominantCategory,
                categories
        );
    }

    /** 规范化跨度。 */
    private List<NormalizedSpan> normalizeSpans(
            List<GenerationSpanQueryService.StoredSpan> storedSpans,
            long windowStartMs,
            long windowEndMs
    ) {
        if (storedSpans == null || storedSpans.isEmpty() || windowEndMs <= windowStartMs) {
            return List.of();
        }
        List<NormalizedSpan> spans = new ArrayList<>();
        for (GenerationSpanQueryService.StoredSpan span : storedSpans) {
            if (span == null || span.startedAt() == null || span.endedAt() == null) {
                continue;
            }
            long startedAtMs = Math.max(windowStartMs, span.startedAt().toEpochMilli());
            long endedAtMs = Math.min(windowEndMs, span.endedAt().toEpochMilli());
            if (endedAtMs <= startedAtMs) {
                continue;
            }
            spans.add(new NormalizedSpan(
                    normalizeCategory(span.category()),
                    normalizeDimension(span.stage()),
                    new Interval(startedAtMs, endedAtMs)
            ));
        }
        return List.copyOf(spans);
    }

    /** 返回{@code attributed}{@code Category}{@code Durations}。 */
    private Map<String, Long> attributedCategoryDurations(List<NormalizedSpan> spans) {
        Map<String, Long> durations = new HashMap<>();
        if (spans.isEmpty()) {
            return durations;
        }
        LinkedHashSet<Long> boundaries = new LinkedHashSet<>();
        spans.stream()
                .flatMap(span -> java.util.stream.Stream.of(
                        span.interval().startedAtMs(), span.interval().endedAtMs()))
                .sorted()
                .forEach(boundaries::add);
        List<Long> orderedBoundaries = List.copyOf(boundaries);
        Comparator<NormalizedSpan> leafFirst = Comparator
                .comparing((NormalizedSpan span) -> PIPELINE_CATEGORY.equals(span.category()))
                .thenComparingLong(span -> span.interval().durationMs())
                .thenComparing(NormalizedSpan::category)
                .thenComparing(NormalizedSpan::stage);
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (int index = 1; index < orderedBoundaries.size(); index++) {
            long segmentStart = orderedBoundaries.get(index - 1);
            long segmentEnd = orderedBoundaries.get(index);
            if (segmentEnd <= segmentStart) {
                continue;
            }
            NormalizedSpan owner = spans.stream()
                    .filter(span -> span.interval().startedAtMs() <= segmentStart
                            && span.interval().endedAtMs() >= segmentEnd)
                    .min(leafFirst)
                    .orElse(null);
            if (owner != null) {
                durations.merge(owner.category(), segmentEnd - segmentStart, Long::sum);
            }
        }
        return durations;
    }

    /** 合并{@code d}时长。 */
    private long mergedDuration(List<Interval> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return 0;
        }
        List<Interval> ordered = intervals.stream()
                .sorted(Comparator.comparingLong(Interval::startedAtMs)
                        .thenComparingLong(Interval::endedAtMs))
                .toList();
        long total = 0;
        long currentStart = ordered.getFirst().startedAtMs();
        long currentEnd = ordered.getFirst().endedAtMs();
        for (int index = 1; index < ordered.size(); index++) {
            Interval next = ordered.get(index);
            if (next.startedAtMs() <= currentEnd) {
                currentEnd = Math.max(currentEnd, next.endedAtMs());
                continue;
            }
            total += currentEnd - currentStart;
            currentStart = next.startedAtMs();
            currentEnd = next.endedAtMs();
        }
        return total + currentEnd - currentStart;
    }

    private long deadlineOvershoot(Instant deadlineAt, Instant effectiveEndAt) {
        if (deadlineAt == null || effectiveEndAt == null || !effectiveEndAt.isAfter(deadlineAt)) {
            return 0;
        }
        return Math.max(0, effectiveEndAt.toEpochMilli() - deadlineAt.toEpochMilli());
    }

    private double percent(long durationMs, long totalLatencyMs) {
        if (durationMs <= 0 || totalLatencyMs <= 0) {
            return 0.0d;
        }
        return Math.round(durationMs * 10_000.0d / totalLatencyMs) / 100.0d;
    }

    private String normalizeCategory(String value) {
        String normalized = normalizeDimension(value);
        return normalized.isEmpty() ? "unknown" : normalized;
    }

    private String normalizeDimension(String value) {
        return value == null || value.isBlank()
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private record NormalizedSpan(String category, String stage, Interval interval) {
    }

    private record Interval(long startedAtMs, long endedAtMs) {
        private long durationMs() {
            return endedAtMs - startedAtMs;
        }
    }
}
