package com.rush.rushaicodemother.service.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProvider;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetrySnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Cached adapter that combines task outcomes, user feedback and durable runtime load. */
@Slf4j
@Service
public class DefaultGenerationRoutingTelemetryProvider implements GenerationRoutingTelemetryProvider {

    private final GenerationTracePersistenceService tracePersistenceService;
    private final GenerationFeedbackRepository feedbackRepository;
    private final DurableGenerationTaskRepository taskRepository;
    private final GenerationTaskExecutorProperties executorProperties;
    private final GenerationRoutingTelemetryProperties properties;
    private final Clock clock;
    private final Cache<CacheKey, GenerationRoutingTelemetrySnapshot> cache;

    public DefaultGenerationRoutingTelemetryProvider(
            GenerationTracePersistenceService tracePersistenceService,
            GenerationFeedbackRepository feedbackRepository,
            DurableGenerationTaskRepository taskRepository,
            GenerationTaskExecutorProperties executorProperties,
            GenerationRoutingTelemetryProperties properties
    ) {
        this(tracePersistenceService, feedbackRepository, taskRepository,
                executorProperties, properties, Clock.systemUTC());
    }

    DefaultGenerationRoutingTelemetryProvider(
            GenerationTracePersistenceService tracePersistenceService,
            GenerationFeedbackRepository feedbackRepository,
            DurableGenerationTaskRepository taskRepository,
            GenerationTaskExecutorProperties executorProperties,
            GenerationRoutingTelemetryProperties properties,
            Clock clock
    ) {
        this.tracePersistenceService = Objects.requireNonNull(tracePersistenceService, "tracePersistenceService");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "feedbackRepository");
        this.taskRepository = Objects.requireNonNull(taskRepository, "taskRepository");
        this.executorProperties = Objects.requireNonNull(executorProperties, "executorProperties");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCachedApplications())
                .expireAfterWrite(properties.getCacheTtl())
                .build();
    }

    @Override
    public GenerationRoutingTelemetrySnapshot snapshot(Long appId, Long userId) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0) {
            return GenerationRoutingTelemetrySnapshot.unavailable();
        }
        return cache.get(new CacheKey(appId, userId), this::loadSafely);
    }

    private GenerationRoutingTelemetrySnapshot loadSafely(CacheKey key) {
        try {
            List<GenerationTracePersistenceService.TaskRecord> recentTasks =
                    tracePersistenceService.listRecentTasksByAppId(key.appId(), properties.getTaskSampleLimit());
            GenerationFeedbackSummary feedback = feedbackRepository.summarizeByAppId(key.appId());
            GenerationTaskLoadSnapshot load = taskRepository.loadCurrentLoad();
            return toSnapshot(recentTasks, feedback, load);
        } catch (RuntimeException failure) {
            log.warn("Generation routing telemetry load failed, appId: {}, error: {}",
                    key.appId(), LogExceptionSanitizer.sanitizeMessage(failure));
            return GenerationRoutingTelemetrySnapshot.unavailable();
        }
    }

    private GenerationRoutingTelemetrySnapshot toSnapshot(
            List<GenerationTracePersistenceService.TaskRecord> records,
            GenerationFeedbackSummary feedback,
            GenerationTaskLoadSnapshot load
    ) {
        List<GenerationTracePersistenceService.TaskRecord> terminalRecords = records == null
                ? List.of()
                : records.stream()
                .filter(Objects::nonNull)
                .filter(record -> record.status() != null && record.status().isTerminal())
                .toList();
        int failedTasks = (int) terminalRecords.stream()
                .filter(record -> record.status() == GenerationTaskStatus.FAILED
                        || record.status() == GenerationTaskStatus.DEADLINE_EXCEEDED)
                .count();
        long averageDurationMs = Math.round(terminalRecords.stream()
                .map(GenerationTracePersistenceService.TaskRecord::durationMs)
                .filter(Objects::nonNull)
                .filter(duration -> duration > 0)
                .mapToLong(Long::longValue)
                .average()
                .orElse(0.0));
        GenerationFeedbackSummary safeFeedback = feedback == null
                ? GenerationFeedbackSummary.empty() : feedback;
        GenerationTaskLoadSnapshot safeLoad = load == null ? GenerationTaskLoadSnapshot.empty() : load;
        return new GenerationRoutingTelemetrySnapshot(
                terminalRecords.size(),
                failedTasks,
                averageDurationMs,
                safeFeedback.feedbackCount(),
                safeFeedback.lowRatingCount(),
                safeFeedback.averageRating(),
                safeLoad.queuedTaskCount(),
                safeLoad.runningTaskCount(),
                safeLoad.waitingApprovalTaskCount(),
                executorProperties.getMaxConcurrency(),
                executorProperties.getQueueCapacity(),
                Instant.now(clock),
                true
        );
    }

    private record CacheKey(Long appId, Long userId) {
    }
}
