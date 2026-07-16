package com.rush.rushaicodemother.monitor;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSpanVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceStageStatsVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSummaryVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceTaskVO;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.span.GenerationSpanObservation;
import com.rush.rushaicodemother.monitor.span.GenerationSpanSink;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
public class GenerationPerformanceMonitorService {

    private static final int MAX_RETAINED_TASKS = 300;
    private static final int DEFAULT_RECENT_LIMIT = 50;
    private static final int MAX_SPAN_DETAIL_LENGTH = 1_000;

    private final ConcurrentMap<String, TaskRecord> taskRecords = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> taskOrder = new ConcurrentLinkedDeque<>();
    private final List<GenerationSpanSink> spanSinks;
    private final Clock clock;

    /** Compatibility constructor for isolated tests and non-Spring callers. */
    public GenerationPerformanceMonitorService() {
        this(List.of(), Clock.systemUTC());
    }

    /** Spring constructor: all registered sinks receive every completed span. */
    @Autowired
    public GenerationPerformanceMonitorService(List<GenerationSpanSink> spanSinks) {
        this(spanSinks, Clock.systemUTC());
    }

    GenerationPerformanceMonitorService(List<GenerationSpanSink> spanSinks, Clock clock) {
        this.spanSinks = spanSinks == null ? List.of() : List.copyOf(spanSinks);
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    public void startTask(String taskId, Long appId, Long userId, String route, String targetType) {
        startTask(taskId, appId, userId, route, targetType, clock.instant());
    }

    public void startTask(String taskId, Long appId, Long userId, String route, String targetType, Instant startAt) {
        startTask(taskId, appId, userId, route, targetType, startAt, null);
    }

    public void startTask(String taskId,
                          Long appId,
                          Long userId,
                          String route,
                          String targetType,
                          Instant startAt,
                          GenerationModeDecision decision) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        TaskRecord record = new TaskRecord(taskId, appId, userId, normalize(route), normalize(targetType),
                startAt == null ? clock.instant() : startAt, decision);
        TaskRecord previous = taskRecords.put(taskId, record);
        if (previous == null) {
            taskOrder.addFirst(taskId);
            trimOldTasks();
        }
    }

    public SpanTimer startSpan(String taskId, String stage) {
        return startSpan(taskId, stage, GenerationSpanCategory.PIPELINE);
    }

    public SpanTimer startSpan(String taskId, String stage, GenerationSpanCategory category) {
        return new SpanTimer(this, taskId, stage, category, clock.instant());
    }

    public void recordSpan(String taskId, String stage, String status, Duration duration, String detail) {
        recordSpan(taskId, stage, GenerationSpanCategory.PIPELINE, status, duration, detail);
    }

    public void recordSpan(String taskId,
                           String stage,
                           GenerationSpanCategory category,
                           String status,
                           Duration duration,
                           String detail) {
        long durationMs = Math.max(0, duration == null ? 0 : duration.toMillis());
        Instant endedAt = clock.instant();
        Instant startedAt = endedAt.minusMillis(durationMs);
        recordCompletedSpan(taskId, stage, category, status, startedAt, endedAt, detail);
    }

    private void recordCompletedSpan(String taskId,
                                     String stage,
                                     GenerationSpanCategory category,
                                     String status,
                                     Instant startedAt,
                                     Instant endedAt,
                                     String detail) {
        if (StrUtil.isBlank(taskId) || StrUtil.isBlank(stage) || category == null
                || startedAt == null || endedAt == null) {
            return;
        }
        Instant safeEndedAt = endedAt.isBefore(startedAt) ? startedAt : endedAt;
        long durationMs = Math.max(0, Duration.between(startedAt, safeEndedAt).toMillis());
        String normalizedStage = StrUtil.subPre(normalize(stage), 96);
        String normalizedStatus = StrUtil.subPre(normalize(status), 32);
        String safeDetail = LogExceptionSanitizer.sanitizeValue(detail, MAX_SPAN_DETAIL_LENGTH);

        TaskRecord record = taskRecords.get(taskId);
        if (record != null) {
            record.addSpan(new SpanRecord(normalizedStage, normalizedStatus, durationMs, safeDetail));
        }

        GenerationSpanObservation observation;
        try {
            observation = new GenerationSpanObservation(
                    UUID.randomUUID().toString(), taskId, normalizedStage, category, normalizedStatus,
                    startedAt, safeEndedAt, durationMs, safeDetail
            );
        } catch (IllegalArgumentException invalidObservation) {
            log.warn("Invalid generation span ignored, taskId: {}, stage: {}, error: {}",
                    LogExceptionSanitizer.sanitizeValue(taskId, 128), normalizedStage,
                    LogExceptionSanitizer.sanitizeMessage(invalidObservation));
            return;
        }
        for (GenerationSpanSink sink : spanSinks) {
            try {
                sink.record(observation);
            } catch (RuntimeException sinkFailure) {
                log.warn("Generation span sink failed without interrupting task, taskId: {}, stage: {}, sink: {}, error: {}",
                        taskId, normalizedStage, sink.getClass().getSimpleName(),
                        LogExceptionSanitizer.sanitizeMessage(sinkFailure));
            }
        }
    }

    public void finishTask(String taskId, String status) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return;
        }
        record.finish(normalize(status));
    }

    public void recordCreateTelemetry(String taskId, Map<String, Object> telemetry) {
        if (StrUtil.isBlank(taskId) || telemetry == null || telemetry.isEmpty()) {
            return;
        }
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return;
        }
        record.recordCreateTelemetry(telemetry);
    }

    public void recordRuntimeTelemetry(String taskId, Map<String, Object> telemetry) {
        if (StrUtil.isBlank(taskId) || telemetry == null || telemetry.isEmpty()) {
            return;
        }
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return;
        }
        record.recordRuntimeTelemetry(telemetry);
    }

    public GenerationPerformanceSummaryVO getSummary(Integer limit) {
        int recentLimit = limit == null || limit <= 0 ? DEFAULT_RECENT_LIMIT : Math.min(limit, MAX_RETAINED_TASKS);
        List<TaskRecord> records = taskOrder.stream()
                .map(taskRecords::get)
                .filter(record -> record != null)
                .toList();
        List<TaskRecord> completed = records.stream()
                .filter(TaskRecord::completed)
                .toList();
        List<Long> totalDurations = completed.stream()
                .map(TaskRecord::totalDurationMs)
                .sorted()
                .toList();

        return GenerationPerformanceSummaryVO.builder()
                .taskCount((long) records.size())
                .successCount(countStatus(records, "success"))
                .failedCount(countStatus(records, "failed"))
                .runningCount(records.stream().filter(record -> !record.completed()).count())
                .avgTotalDurationMs(avg(totalDurations))
                .p50TotalDurationMs(percentile(totalDurations, 0.50))
                .p90TotalDurationMs(percentile(totalDurations, 0.90))
                .stageStats(buildStageStats(records))
                .recentTasks(records.stream()
                        .limit(recentLimit)
                        .map(TaskRecord::toVO)
                        .toList())
                .build();
    }

    private List<GenerationPerformanceStageStatsVO> buildStageStats(List<TaskRecord> records) {
        Map<String, List<Long>> grouped = records.stream()
                .flatMap(record -> record.spans().stream())
                .collect(Collectors.groupingBy(
                        SpanRecord::stage,
                        Collectors.mapping(SpanRecord::durationMs, Collectors.toList())
                ));
        return grouped.entrySet().stream()
                .map(entry -> {
                    List<Long> durations = entry.getValue().stream().sorted().toList();
                    return GenerationPerformanceStageStatsVO.builder()
                            .stage(entry.getKey())
                            .count((long) durations.size())
                            .avgDurationMs(avg(durations))
                            .p50DurationMs(percentile(durations, 0.50))
                            .p90DurationMs(percentile(durations, 0.90))
                            .maxDurationMs(durations.isEmpty() ? 0 : durations.getLast())
                            .build();
                })
                .sorted(Comparator.comparing(GenerationPerformanceStageStatsVO::getP90DurationMs,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private long countStatus(List<TaskRecord> records, String status) {
        return records.stream().filter(record -> status.equals(record.status())).count();
    }

    private Long avg(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private Long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues == null || sortedValues.isEmpty()) {
            return 0L;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private void trimOldTasks() {
        while (taskOrder.size() > MAX_RETAINED_TASKS) {
            String taskId = taskOrder.pollLast();
            if (taskId != null) {
                taskRecords.remove(taskId);
            }
        }
    }

    private static String normalize(String value) {
        return StrUtil.blankToDefault(value, "unknown").trim().toLowerCase().replaceAll("\\s+", "_");
    }

    public static final class SpanTimer implements AutoCloseable {

        private final GenerationPerformanceMonitorService monitorService;
        private final String taskId;
        private final String stage;
        private final GenerationSpanCategory category;
        private final Instant startAt;
        private final AtomicBoolean closed = new AtomicBoolean();

        private SpanTimer(GenerationPerformanceMonitorService monitorService,
                          String taskId,
                          String stage,
                          GenerationSpanCategory category,
                          Instant startAt) {
            this.monitorService = monitorService;
            this.taskId = taskId;
            this.stage = stage;
            this.category = category;
            this.startAt = startAt;
        }

        public void success() {
            close("success", "");
        }

        public void failed(String detail) {
            close("failed", detail);
        }

        public void close(String status, String detail) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            monitorService.recordCompletedSpan(
                    taskId, stage, category, status, startAt, monitorService.clock.instant(), detail
            );
        }

        @Override
        public void close() {
            success();
        }
    }

    private static final class TaskRecord {

        private final String taskId;
        private final Long appId;
        private final Long userId;
        private final String route;
        private final String targetType;
        private final Instant startAt;
        private final String mode;
        private final String routerReason;
        private final String fallbackPolicy;
        private final String fallbackReason;
        private final String validationLevel;
        private final List<SpanRecord> spans = new ArrayList<>();
        private volatile CreateTelemetryRecord createTelemetry = CreateTelemetryRecord.empty();
        private volatile RuntimeTelemetryRecord runtimeTelemetry = RuntimeTelemetryRecord.empty();
        private volatile String status = "running";
        private volatile Instant endAt;

        private TaskRecord(String taskId,
                           Long appId,
                           Long userId,
                           String route,
                           String targetType,
                           Instant startAt,
                           GenerationModeDecision decision) {
            this.taskId = taskId;
            this.appId = appId;
            this.userId = userId;
            this.route = route;
            this.targetType = targetType;
            this.startAt = startAt;
            this.mode = decision == null ? "unknown" : normalize(decision.mode().name());
            this.routerReason = decision == null ? "" : StrUtil.subPre(decision.reason(), 300);
            this.fallbackPolicy = decision == null ? "unknown" : normalize(decision.fallbackPolicy().name());
            this.fallbackReason = decision == null ? "" : StrUtil.subPre(decision.fallbackReason(), 300);
            this.validationLevel = decision == null ? "unknown" : normalize(decision.expectedValidationLevel().name());
        }

        private synchronized void addSpan(SpanRecord spanRecord) {
            spans.add(spanRecord);
        }

        private synchronized List<SpanRecord> spans() {
            return List.copyOf(spans);
        }

        private void recordCreateTelemetry(Map<String, Object> telemetry) {
            this.createTelemetry = CreateTelemetryRecord.from(telemetry);
        }

        private void recordRuntimeTelemetry(Map<String, Object> telemetry) {
            this.runtimeTelemetry = runtimeTelemetry.merge(RuntimeTelemetryRecord.from(telemetry));
        }

        private void finish(String status) {
            this.status = status;
            this.endAt = Instant.now();
        }

        private boolean completed() {
            return endAt != null;
        }

        private String status() {
            return status;
        }

        private long totalDurationMs() {
            Instant end = endAt == null ? Instant.now() : endAt;
            return Math.max(0, Duration.between(startAt, end).toMillis());
        }

        private GenerationPerformanceTaskVO toVO() {
            return GenerationPerformanceTaskVO.builder()
                    .taskId(taskId)
                    .appId(appId)
                    .userId(userId)
                    .route(route)
                    .mode(mode)
                    .routerReason(routerReason)
                    .fallbackPolicy(fallbackPolicy)
                    .fallbackReason(fallbackReason)
                    .validationLevel(validationLevel)
                    .baseTemplate(createTelemetry.baseTemplate())
                    .modules(createTelemetry.modules())
                    .slotGroupCount(createTelemetry.slotGroupCount())
                    .aiCallCount(createTelemetry.aiCallCount())
                    .patchCount(createTelemetry.patchCount())
                    .validationDurationMs(createTelemetry.validationDurationMs())
                    .createFallback(createTelemetry.fallback())
                    .modelName(runtimeTelemetry.modelName())
                    .firstTokenLatencyMs(runtimeTelemetry.firstTokenLatencyMs())
                    .totalAiDurationMs(runtimeTelemetry.totalAiDurationMs())
                    .toolCallCount(runtimeTelemetry.toolCallCount())
                    .toolDurationMs(runtimeTelemetry.toolDurationMs())
                    .repairRounds(runtimeTelemetry.repairRounds())
                    .targetType(targetType)
                    .status(status)
                    .totalDurationMs(totalDurationMs())
                    .startTime(toLocalDateTime(startAt))
                    .endTime(endAt == null ? null : toLocalDateTime(endAt))
                    .spans(spans().stream().map(SpanRecord::toVO).toList())
                    .build();
        }

        private LocalDateTime toLocalDateTime(Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
    }

    private record SpanRecord(String stage, String status, Long durationMs, String detail) {

        private GenerationPerformanceSpanVO toVO() {
            return GenerationPerformanceSpanVO.builder()
                    .stage(stage)
                    .status(status)
                    .durationMs(durationMs)
                    .detail(detail)
                    .build();
        }
    }

    private record CreateTelemetryRecord(
            String baseTemplate,
            List<String> modules,
            Integer slotGroupCount,
            Integer aiCallCount,
            Integer patchCount,
            Long validationDurationMs,
            Boolean fallback
    ) {
        private static CreateTelemetryRecord empty() {
            return new CreateTelemetryRecord("", List.of(), 0, 0, 0, 0L, false);
        }

        private static CreateTelemetryRecord from(Map<String, Object> telemetry) {
            return new CreateTelemetryRecord(
                    stringValue(telemetry.get("baseTemplate")),
                    stringList(telemetry.get("modules")),
                    intValue(telemetry.get("slotGroupCount")),
                    intValue(telemetry.get("aiCallCount")),
                    intValue(telemetry.get("patchCount")),
                    longValue(telemetry.get("validationDurationMs")),
                    boolValue(telemetry.get("fallback"))
            );
        }

        private static String stringValue(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        private static List<String> stringList(Object value) {
            if (value instanceof List<?> list) {
                return list.stream().map(String::valueOf).toList();
            }
            return List.of();
        }

        private static Integer intValue(Object value) {
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return value == null ? 0 : Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0;
            }
        }

        private static Long longValue(Object value) {
            if (value instanceof Number number) {
                return number.longValue();
            }
            try {
                return value == null ? 0L : Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        private static Boolean boolValue(Object value) {
            if (value instanceof Boolean bool) {
                return bool;
            }
            return value != null && Boolean.parseBoolean(String.valueOf(value));
        }
    }

    private record RuntimeTelemetryRecord(
            String modelName,
            Long firstTokenLatencyMs,
            Long totalAiDurationMs,
            Integer toolCallCount,
            Long toolDurationMs,
            Integer repairRounds
    ) {
        private static RuntimeTelemetryRecord empty() {
            return new RuntimeTelemetryRecord("", 0L, 0L, 0, 0L, 0);
        }

        private static RuntimeTelemetryRecord from(Map<String, Object> telemetry) {
            return new RuntimeTelemetryRecord(
                    CreateTelemetryRecord.stringValue(telemetry.get("modelName")),
                    CreateTelemetryRecord.longValue(telemetry.get("firstTokenLatencyMs")),
                    CreateTelemetryRecord.longValue(telemetry.get("totalAiDurationMs")),
                    CreateTelemetryRecord.intValue(telemetry.get("toolCallCount")),
                    CreateTelemetryRecord.longValue(telemetry.get("toolDurationMs")),
                    CreateTelemetryRecord.intValue(telemetry.get("repairRounds"))
            );
        }

        private RuntimeTelemetryRecord merge(RuntimeTelemetryRecord other) {
            if (other == null) {
                return this;
            }
            return new RuntimeTelemetryRecord(
                    StrUtil.isNotBlank(other.modelName()) ? other.modelName() : modelName,
                    firstNonZero(other.firstTokenLatencyMs(), firstTokenLatencyMs),
                    firstNonZero(other.totalAiDurationMs(), totalAiDurationMs),
                    firstNonZero(other.toolCallCount(), toolCallCount),
                    firstNonZero(other.toolDurationMs(), toolDurationMs),
                    firstNonZero(other.repairRounds(), repairRounds)
            );
        }

        private static Long firstNonZero(Long preferred, Long fallback) {
            return preferred != null && preferred > 0 ? preferred : fallback;
        }

        private static Integer firstNonZero(Integer preferred, Integer fallback) {
            return preferred != null && preferred > 0 ? preferred : fallback;
        }
    }
}
