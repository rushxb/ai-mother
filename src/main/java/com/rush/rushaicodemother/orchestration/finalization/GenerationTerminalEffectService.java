package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.config.GenerationTerminalEffectProperties;
import com.rush.rushaicodemother.monitor.GenerationTerminalEffectMetricsCollector;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationTerminalStreamEventFactory;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationProvisionalPreviewLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 终态提交后补发事件并按执行围栏回收资源。 */
@Service
@Slf4j
public class GenerationTerminalEffectService {

    private final GenerationTerminalEffectRepository repository;
    private final GenerationEventPublisher eventPublisher;
    private final GenerationExecutionWorkspaceService workspaceService;
    private final GenerationProvisionalPreviewLifecycle previewLifecycle;
    private final GenerationEventStream generationEventStream;
    private final GenerationTerminalEffectProperties properties;
    private final GenerationTerminalEffectMetricsCollector metrics;
    private final Clock clock;
    private final String leaseOwner;

    @Autowired
    public GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                           GenerationEventPublisher eventPublisher,
                                           GenerationExecutionWorkspaceService workspaceService,
                                           GenerationProvisionalPreviewLifecycle previewLifecycle,
                                           GenerationEventStream generationEventStream,
                                           GenerationTerminalEffectProperties properties,
                                           GenerationTerminalEffectMetricsCollector metrics) {
        this(repository, eventPublisher, workspaceService, previewLifecycle, generationEventStream,
                properties, metrics,
                Clock.systemUTC(), "terminal-effects-" + UUID.randomUUID());
    }

    GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                    GenerationEventPublisher eventPublisher,
                                    GenerationExecutionWorkspaceService workspaceService,
                                    GenerationProvisionalPreviewLifecycle previewLifecycle,
                                    Clock clock,
                                    String leaseOwner) {
        this(repository, eventPublisher, workspaceService, previewLifecycle,
                NoOpGenerationEventStream.INSTANCE,
                new GenerationTerminalEffectProperties(),
                GenerationTerminalEffectMetricsCollector.noOp(), clock, leaseOwner);
    }

    GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                    GenerationEventPublisher eventPublisher,
                                    GenerationExecutionWorkspaceService workspaceService,
                                    GenerationProvisionalPreviewLifecycle previewLifecycle,
                                    GenerationEventStream generationEventStream,
                                    Clock clock,
                                    String leaseOwner) {
        this(repository, eventPublisher, workspaceService, previewLifecycle, generationEventStream,
                new GenerationTerminalEffectProperties(),
                GenerationTerminalEffectMetricsCollector.noOp(), clock, leaseOwner);
    }

    GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                    GenerationEventPublisher eventPublisher,
                                    GenerationExecutionWorkspaceService workspaceService,
                                    GenerationProvisionalPreviewLifecycle previewLifecycle,
                                    GenerationEventStream generationEventStream,
                                    GenerationTerminalEffectProperties properties,
                                    GenerationTerminalEffectMetricsCollector metrics,
                                    Clock clock,
                                    String leaseOwner) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.workspaceService = workspaceService;
        this.previewLifecycle = previewLifecycle;
        this.generationEventStream = generationEventStream;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${app.generation.terminal-effects.scan-interval:2s}")
    public void processPending() {
        processBatch();
    }

    /** 执行一个批次并返回成功完成的工作项数量。 */
    int processBatch() {
        Instant startedAt = clock.instant();
        String batchStatus = "success";
        try {
            Instant now = clock.instant();
            List<GenerationTerminalEffect> effects = repository.claimBatch(
                    now, now.plus(properties.getLeaseDuration()), leaseOwner,
                    properties.getBatchSize(), properties.getMaxAttempts());
            List<GenerationTerminalEffect> claimed = effects == null ? List.of() : effects;
            metrics.recordItems("claimed", claimed.size());
            int completed = 0;
            for (GenerationTerminalEffect effect : claimed) {
                try {
                    if (processOne(effect)) {
                        completed++;
                    }
                } catch (RuntimeException failure) {
                    metrics.recordItems("processing_error", 1);
                    // 当前记录的租约到期后会重试；不能让它阻塞同批其他用户的终态。
                    log.error("终态副作用工作项异常，继续处理同批任务，taskId: {}，error: {}",
                            effect.taskId(), LogExceptionSanitizer.sanitizeMessage(failure));
                }
            }
            return completed;
        } catch (RuntimeException failure) {
            batchStatus = "error";
            throw failure;
        } finally {
            Instant completedAt = clock.instant();
            metrics.recordBatch(batchStatus, nonNegativeDuration(startedAt, completedAt));
            refreshBacklog(completedAt);
        }
    }

    private boolean processOne(GenerationTerminalEffect effect) {
        long epoch = effect.command().executionFence().executionEpoch();
        List<String> failures = new ArrayList<>(4);
        if (!attempt(effect, GenerationTerminalEffectOperation.EVENT_PUBLISH,
                () -> publishTerminalEvent(effect), failures)) {
            return false;
        }
        if (!attempt(effect, GenerationTerminalEffectOperation.TASK_STREAM_COMPLETE,
                () -> generationEventStream.complete(
                        effect.taskId(), GenerationTerminalStreamEventFactory.create(
                                effect.taskId(), effect.command().status())), failures)) {
            return false;
        }
        if (!attempt(effect, GenerationTerminalEffectOperation.PREVIEW_STOP,
                () -> previewLifecycle.stopForTerminal(
                        effect.appId(), effect.command().executionFence()), failures)) {
            return false;
        }
        GenerationExecutionWorkspaceService.CleanupPolicy policy =
                effect.command().status() == GenerationTaskStatus.FAILED
                        ? GenerationExecutionWorkspaceService.CleanupPolicy.QUARANTINE
                        : GenerationExecutionWorkspaceService.CleanupPolicy.DELETE;
        if (!attempt(effect, GenerationTerminalEffectOperation.WORKSPACE_CLEAR,
                () -> workspaceService.clear(
                        effect.command().executionFence(), effect.appId(), policy), failures)) {
            return false;
        }

        if (failures.isEmpty()) {
            boolean completed = repository.markCompleted(
                    effect.taskId(), epoch, leaseOwner, clock.instant());
            metrics.recordItems(completed ? "completed" : "lease_lost", 1);
            return completed;
        }
        Instant failedAt = clock.instant();
        String aggregatedFailure = String.join("; ", failures);
        boolean recorded = repository.markFailed(
                effect.taskId(), epoch, leaseOwner, aggregatedFailure, failedAt,
                failedAt.plus(retryDelay(effect.attempts())));
        if (!recorded) {
            metrics.recordItems("lease_lost", 1);
        } else if (effect.attempts() >= properties.getMaxAttempts()) {
            metrics.recordItems("dead_letter", 1);
        } else {
            metrics.recordItems("retry_scheduled", 1);
        }
        log.warn("终态副作用部分失败，taskId: {}，attempt: {}，error: {}",
                effect.taskId(), effect.attempts(), aggregatedFailure);
        return false;
    }

    /**
     * 单个动作失败不阻塞其他动作；已有回执的动作不再执行。
     *
     * @return 当前 worker 仍持有工作项租约时返回 {@code true}
     */
    private boolean attempt(GenerationTerminalEffect effect,
                            GenerationTerminalEffectOperation operation,
                            Runnable action,
                            List<String> failures) {
        if (!effect.pending(operation)) {
            return true;
        }
        try {
            action.run();
        } catch (RuntimeException failure) {
            failures.add(operation.metricName() + ":"
                    + LogExceptionSanitizer.sanitizeMessage(failure));
            return true;
        }
        long epoch = effect.command().executionFence().executionEpoch();
        boolean recorded = repository.markOperationCompleted(
                effect.taskId(), epoch, leaseOwner, operation, clock.instant());
        if (!recorded) {
            metrics.recordItems("lease_lost", 1);
            log.warn("终态副作用回执写入失去租约，taskId: {}，operation: {}",
                    effect.taskId(), operation.metricName());
            return false;
        }
        metrics.recordItems("operation_completed", 1);
        return true;
    }

    private Duration retryDelay(int attempts) {
        int exponent = Math.max(0, Math.min(20, attempts - 1));
        Duration candidate;
        try {
            candidate = properties.getInitialRetryDelay().multipliedBy(1L << exponent);
        } catch (ArithmeticException overflow) {
            return properties.getMaxRetryDelay();
        }
        return candidate.compareTo(properties.getMaxRetryDelay()) > 0
                ? properties.getMaxRetryDelay() : candidate;
    }

    private void refreshBacklog(Instant observedAt) {
        try {
            metrics.updateBacklog(repository.inspectBacklog(
                    observedAt, properties.getMaxAttempts()), observedAt);
            metrics.recordBacklogRefresh("success");
        } catch (RuntimeException failure) {
            metrics.recordBacklogRefresh("error");
            log.warn("终态副作用积压刷新失败，error: {}",
                    LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private Duration nonNegativeDuration(Instant startedAt, Instant completedAt) {
        Duration duration = Duration.between(startedAt, completedAt);
        return duration.isNegative() ? Duration.ZERO : duration;
    }

    private void publishTerminalEvent(GenerationTerminalEffect effect) {
        GenerationEventType type = switch (effect.command().status()) {
            case SUCCESS -> GenerationEventType.TASK_DONE;
            case CANCELLED -> GenerationEventType.TASK_CANCELLED;
            default -> GenerationEventType.TASK_FAILED;
        };
        eventPublisher.publishIdempotently(new GenerationEvent(
                effect.appId(), effect.userId(), type,
                effect.command().status() == GenerationTaskStatus.SUCCESS
                        ? "生成任务已发布" : "生成任务已结束",
                Map.of("eventId", effect.eventId(), "taskId", effect.taskId(),
                        "route", effect.route() == null ? "unknown" : effect.route(),
                        "status", effect.command().status().getValue()),
                clock.instant()));
    }

    private enum NoOpGenerationEventStream implements GenerationEventStream {
        INSTANCE;

        @Override
        public void publish(String taskId,
                            com.rush.rushaicodemother.core.handler.GenerationStreamEvent event) {
        }

        @Override
        public void complete(String taskId) {
        }

        @Override
        public boolean available(String taskId) {
            return false;
        }

        @Override
        public reactor.core.publisher.Flux<SequencedGenerationEvent> stream(
                String taskId, long afterSequence) {
            return reactor.core.publisher.Flux.empty();
        }
    }
}
