package com.rush.rushaicodemother.monitor;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSpanVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceStageStatsVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceSummaryVO;
import com.rush.rushaicodemother.model.vo.GenerationPerformanceTaskVO;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

@Service
public class GenerationPerformanceMonitorService {

    private static final int MAX_RETAINED_TASKS = 300;
    private static final int DEFAULT_RECENT_LIMIT = 50;

    private final ConcurrentMap<String, TaskRecord> taskRecords = new ConcurrentHashMap<>();
    private final ConcurrentLinkedDeque<String> taskOrder = new ConcurrentLinkedDeque<>();

    public void startTask(String taskId, Long appId, Long userId, String route, String targetType) {
        startTask(taskId, appId, userId, route, targetType, Instant.now());
    }

    public void startTask(String taskId, Long appId, Long userId, String route, String targetType, Instant startAt) {
        if (StrUtil.isBlank(taskId)) {
            return;
        }
        TaskRecord record = new TaskRecord(taskId, appId, userId, normalize(route), normalize(targetType),
                startAt == null ? Instant.now() : startAt);
        TaskRecord previous = taskRecords.put(taskId, record);
        if (previous == null) {
            taskOrder.addFirst(taskId);
            trimOldTasks();
        }
    }

    public SpanTimer startSpan(String taskId, String stage) {
        return new SpanTimer(this, taskId, stage, Instant.now());
    }

    public void recordSpan(String taskId, String stage, String status, Duration duration, String detail) {
        if (StrUtil.isBlank(taskId) || StrUtil.isBlank(stage)) {
            return;
        }
        TaskRecord record = taskRecords.get(taskId);
        if (record == null) {
            return;
        }
        record.addSpan(new SpanRecord(
                normalize(stage),
                normalize(status),
                Math.max(0, duration == null ? 0 : duration.toMillis()),
                StrUtil.subPre(StrUtil.blankToDefault(detail, ""), 300)
        ));
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

    private String normalize(String value) {
        return StrUtil.blankToDefault(value, "unknown").trim().toLowerCase().replaceAll("\\s+", "_");
    }

    public static final class SpanTimer implements AutoCloseable {

        private final GenerationPerformanceMonitorService monitorService;
        private final String taskId;
        private final String stage;
        private final Instant startAt;
        private boolean closed;

        private SpanTimer(GenerationPerformanceMonitorService monitorService,
                          String taskId,
                          String stage,
                          Instant startAt) {
            this.monitorService = monitorService;
            this.taskId = taskId;
            this.stage = stage;
            this.startAt = startAt;
        }

        public void success() {
            close("success", "");
        }

        public void failed(String detail) {
            close("failed", detail);
        }

        public void close(String status, String detail) {
            if (closed) {
                return;
            }
            closed = true;
            monitorService.recordSpan(taskId, stage, status, Duration.between(startAt, Instant.now()), detail);
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
        private final List<SpanRecord> spans = new ArrayList<>();
        private volatile String status = "running";
        private volatile Instant endAt;

        private TaskRecord(String taskId, Long appId, Long userId, String route, String targetType, Instant startAt) {
            this.taskId = taskId;
            this.appId = appId;
            this.userId = userId;
            this.route = route;
            this.targetType = targetType;
            this.startAt = startAt;
        }

        private synchronized void addSpan(SpanRecord spanRecord) {
            spans.add(spanRecord);
        }

        private synchronized List<SpanRecord> spans() {
            return List.copyOf(spans);
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
}
