package com.rush.rushaicodemother.service.routing;

import com.github.benmanes.caffeine.cache.AsyncCacheLoader;
import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationRoutingTelemetryMetricsCollector;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackRepository;
import com.rush.rushaicodemother.orchestration.feedback.GenerationFeedbackSummary;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProperties;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetryProvider;
import com.rush.rushaicodemother.orchestration.router.GenerationRoutingTelemetrySnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecutorProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.service.trace.GenerationTracePersistenceService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** 缓存适配器，结合了任务结果、用户反馈和持久的运行时负载。 */
@Slf4j
@Service
public class DefaultGenerationRoutingTelemetryProvider
        implements GenerationRoutingTelemetryProvider, AutoCloseable {

    private final GenerationTracePersistenceService tracePersistenceService;
    private final GenerationFeedbackRepository feedbackRepository;
    private final DurableGenerationTaskRepository taskRepository;
    private final GenerationTaskExecutorProperties executorProperties;
    private final GenerationRoutingTelemetryProperties properties;
    private final GenerationRoutingTelemetryMetricsCollector metricsCollector;
    private final Clock clock;
    private final AsyncLoadingCache<CacheKey, GenerationRoutingTelemetrySnapshot> cache;
    private final ExecutorService loadExecutor;
    private final Semaphore loadPermits;
    private final AtomicBoolean closed = new AtomicBoolean();

    @Autowired
    public DefaultGenerationRoutingTelemetryProvider(
            GenerationTracePersistenceService tracePersistenceService,
            GenerationFeedbackRepository feedbackRepository,
            DurableGenerationTaskRepository taskRepository,
            GenerationTaskExecutorProperties executorProperties,
            GenerationRoutingTelemetryProperties properties,
            GenerationRoutingTelemetryMetricsCollector metricsCollector
    ) {
        this(tracePersistenceService, feedbackRepository, taskRepository,
                executorProperties, properties, metricsCollector, Clock.systemUTC());
    }

    /** 创建默认生成路由遥测提供方实例并完成必要的依赖和初始状态设置。 */
    DefaultGenerationRoutingTelemetryProvider(
            GenerationTracePersistenceService tracePersistenceService,
            GenerationFeedbackRepository feedbackRepository,
            DurableGenerationTaskRepository taskRepository,
            GenerationTaskExecutorProperties executorProperties,
            GenerationRoutingTelemetryProperties properties,
            Clock clock
    ) {
        this(tracePersistenceService, feedbackRepository, taskRepository, executorProperties,
                properties, GenerationRoutingTelemetryMetricsCollector.noOp(), clock);
    }

    DefaultGenerationRoutingTelemetryProvider(
            GenerationTracePersistenceService tracePersistenceService,
            GenerationFeedbackRepository feedbackRepository,
            DurableGenerationTaskRepository taskRepository,
            GenerationTaskExecutorProperties executorProperties,
            GenerationRoutingTelemetryProperties properties,
            GenerationRoutingTelemetryMetricsCollector metricsCollector,
            Clock clock
    ) {
        this.tracePersistenceService = Objects.requireNonNull(tracePersistenceService, "生成追踪持久化服务不能为空");
        this.feedbackRepository = Objects.requireNonNull(feedbackRepository, "生成反馈仓储不能为空");
        this.taskRepository = Objects.requireNonNull(taskRepository, "生成任务仓储不能为空");
        this.executorProperties = Objects.requireNonNull(executorProperties, "生成任务执行器配置不能为空");
        this.properties = Objects.requireNonNull(properties, "生成路由遥测配置不能为空");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "生成路由遥测指标不能为空");
        this.clock = Objects.requireNonNull(clock, "业务时钟不能为空");
        this.loadPermits = new Semaphore(properties.getMaxConcurrentLoads());
        this.loadExecutor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("generation-routing-telemetry-", 0).factory());
        this.cache = Caffeine.newBuilder()
                .maximumSize(properties.getMaxCachedApplications())
                .refreshAfterWrite(properties.getCacheTtl())
                .expireAfterWrite(properties.getStaleRetention())
                .executor(Runnable::run)
                .buildAsync(new AsyncCacheLoader<>() {
                    @Override
                    public CompletableFuture<? extends GenerationRoutingTelemetrySnapshot> asyncLoad(
                            CacheKey key,
                            Executor ignored
                    ) {
                        return loadAsync(key, null);
                    }

                    @Override
                    public CompletableFuture<? extends GenerationRoutingTelemetrySnapshot> asyncReload(
                            CacheKey key,
                            GenerationRoutingTelemetrySnapshot oldValue,
                            Executor ignored
                    ) {
                        return loadAsync(key, oldValue);
                    }
                });
    }

    /**
 * 返回快照。
 *
 * @param appId 应用编号
 * @param userId 用户编号
 * @return 默认生成路由遥测提供方
 */
    @Override
    public GenerationRoutingTelemetrySnapshot snapshot(Long appId, Long userId) {
        if (appId == null || appId <= 0 || userId == null || userId <= 0) {
            return GenerationRoutingTelemetrySnapshot.unavailable();
        }
        long startedAtNanos = System.nanoTime();
        CacheKey key = new CacheKey(appId, userId);
        boolean cached = cache.getIfPresent(key) != null;
        CompletableFuture<GenerationRoutingTelemetrySnapshot> loading = cache.get(key);
        try {
            GenerationRoutingTelemetrySnapshot snapshot = loading.get(
                    properties.getColdLoadTimeout().toNanos(), TimeUnit.NANOSECONDS);
            GenerationRoutingTelemetrySnapshot resolved = snapshot == null
                    ? GenerationRoutingTelemetrySnapshot.unavailable()
                    : snapshot;
            metricsCollector.recordSnapshot(
                    cached ? "cache" : resolved.available() ? "loaded" : "unavailable",
                    elapsedSince(startedAtNanos));
            return resolved;
        } catch (TimeoutException timeout) {
            metricsCollector.recordSnapshot("timeout", elapsedSince(startedAtNanos));
            return GenerationRoutingTelemetrySnapshot.unavailable();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            metricsCollector.recordSnapshot("interrupted", elapsedSince(startedAtNanos));
            return GenerationRoutingTelemetrySnapshot.unavailable();
        } catch (ExecutionException loadFailure) {
            String status = loadFailure.getCause() instanceof RejectedExecutionException
                    ? "saturated" : "failed";
            metricsCollector.recordSnapshot(status, elapsedSince(startedAtNanos));
            return GenerationRoutingTelemetrySnapshot.unavailable();
        }
    }

    private CompletableFuture<GenerationRoutingTelemetrySnapshot> loadAsync(
            CacheKey key,
            GenerationRoutingTelemetrySnapshot staleSnapshot
    ) {
        if (closed.get() || !loadPermits.tryAcquire()) {
            metricsCollector.recordLoad("saturated", Duration.ZERO);
            return CompletableFuture.completedFuture(fallbackSnapshot(staleSnapshot));
        }
        long startedAtNanos = System.nanoTime();
        try {
            return CompletableFuture.supplyAsync(() -> load(key), loadExecutor)
                    .handle((snapshot, failure) -> {
                        loadPermits.release();
                        metricsCollector.recordLoad(
                                failure == null ? "success" : "failed",
                                elapsedSince(startedAtNanos));
                        return failure == null ? snapshot : fallbackSnapshot(staleSnapshot);
                    });
        } catch (RejectedExecutionException rejected) {
            loadPermits.release();
            metricsCollector.recordLoad("saturated", elapsedSince(startedAtNanos));
            return CompletableFuture.completedFuture(fallbackSnapshot(staleSnapshot));
        }
    }

    private GenerationRoutingTelemetrySnapshot fallbackSnapshot(
            GenerationRoutingTelemetrySnapshot staleSnapshot
    ) {
        if (staleSnapshot == null || !staleSnapshot.available()
                || staleSnapshot.capturedAt() == null) {
            return GenerationRoutingTelemetrySnapshot.unavailable();
        }
        Duration age = Duration.between(staleSnapshot.capturedAt(), clock.instant());
        return !age.isNegative() && age.compareTo(properties.getStaleRetention()) <= 0
                ? staleSnapshot
                : GenerationRoutingTelemetrySnapshot.unavailable();
    }

    private GenerationRoutingTelemetrySnapshot load(CacheKey key) {
        try {
            List<GenerationTracePersistenceService.TaskRecord> recentTasks =
                    tracePersistenceService.listRecentTasksByAppId(key.appId(), properties.getTaskSampleLimit());
            GenerationFeedbackSummary feedback = feedbackRepository.summarizeByAppId(key.appId());
            GenerationTaskLoadSnapshot load = taskRepository.loadCurrentLoad();
            return toSnapshot(recentTasks, feedback, load);
        } catch (RuntimeException failure) {
            log.warn("生成路由遥测加载失败，appId: {}, error: {}",
                    key.appId(), LogExceptionSanitizer.sanitizeMessage(failure));
            throw failure;
        }
    }

    @PreDestroy
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        loadExecutor.shutdownNow();
        try {
            if (!loadExecutor.awaitTermination(
                    properties.getShutdownTimeout().toNanos(), TimeUnit.NANOSECONDS)) {
                log.warn("生成路由遥测后台加载器未在关闭窗口内结束");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private Duration elapsedSince(long startedAtNanos) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAtNanos));
    }

    /** 将当前对象转换为快照。 */
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
