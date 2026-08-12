package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.event.GenerationEvent;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationProvisionalPreviewLifecycle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** 终态提交后补发事件并按执行围栏回收资源。 */
@Service
@Slf4j
public class GenerationTerminalEffectService {

    private static final int BATCH_SIZE = 100;
    private static final int MAX_ATTEMPTS = 10;
    private static final Duration LEASE_DURATION = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    private final GenerationTerminalEffectRepository repository;
    private final GenerationEventPublisher eventPublisher;
    private final GenerationExecutionWorkspaceService workspaceService;
    private final GenerationProvisionalPreviewLifecycle previewLifecycle;
    private final Clock clock;
    private final String leaseOwner;

    @Autowired
    public GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                           GenerationEventPublisher eventPublisher,
                                           GenerationExecutionWorkspaceService workspaceService,
                                           GenerationProvisionalPreviewLifecycle previewLifecycle) {
        this(repository, eventPublisher, workspaceService, previewLifecycle, Clock.systemUTC(),
                "terminal-effects-" + UUID.randomUUID());
    }

    GenerationTerminalEffectService(GenerationTerminalEffectRepository repository,
                                    GenerationEventPublisher eventPublisher,
                                    GenerationExecutionWorkspaceService workspaceService,
                                    GenerationProvisionalPreviewLifecycle previewLifecycle,
                                    Clock clock,
                                    String leaseOwner) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.workspaceService = workspaceService;
        this.previewLifecycle = previewLifecycle;
        this.clock = clock;
        this.leaseOwner = leaseOwner;
    }

    @Scheduled(fixedDelayString = "${app.generation.terminal-effects.scan-interval:2s}")
    public void processPending() {
        Instant now = clock.instant();
        for (GenerationTerminalEffect effect : repository.claimBatch(
                now, now.plus(LEASE_DURATION), leaseOwner, BATCH_SIZE, MAX_ATTEMPTS)) {
            processOne(effect);
        }
    }

    private void processOne(GenerationTerminalEffect effect) {
        long epoch = effect.command().executionFence().executionEpoch();
        try {
            publishTerminalEvent(effect);
            previewLifecycle.stopForTerminal(effect.appId(), effect.command().executionFence());
            GenerationExecutionWorkspaceService.CleanupPolicy policy =
                    effect.command().status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.FAILED
                            ? GenerationExecutionWorkspaceService.CleanupPolicy.QUARANTINE
                            : GenerationExecutionWorkspaceService.CleanupPolicy.DELETE;
            workspaceService.clear(effect.command().executionFence(), effect.appId(), policy);
            repository.markCompleted(effect.taskId(), epoch, leaseOwner, clock.instant());
        } catch (RuntimeException failure) {
            Instant failedAt = clock.instant();
            repository.markFailed(effect.taskId(), epoch, leaseOwner,
                    LogExceptionSanitizer.sanitizeMessage(failure), failedAt,
                    failedAt.plus(RETRY_DELAY.multipliedBy(Math.min(effect.attempts(), 12))));
            log.warn("终态副作用处理失败，taskId: {}，attempt: {}，error: {}",
                    effect.taskId(), effect.attempts(), LogExceptionSanitizer.sanitizeMessage(failure));
        }
    }

    private void publishTerminalEvent(GenerationTerminalEffect effect) {
        GenerationEventType type = switch (effect.command().status()) {
            case SUCCESS -> GenerationEventType.TASK_DONE;
            case CANCELLED -> GenerationEventType.TASK_CANCELLED;
            default -> GenerationEventType.TASK_FAILED;
        };
        eventPublisher.publishIdempotently(new GenerationEvent(
                effect.appId(), effect.userId(), type,
                effect.command().status() == com.rush.rushaicodemother.model.enums.GenerationTaskStatus.SUCCESS
                        ? "生成任务已发布" : "生成任务已结束",
                Map.of("eventId", effect.eventId(), "taskId", effect.taskId(),
                        "route", effect.route() == null ? "unknown" : effect.route(),
                        "status", effect.command().status().getValue()),
                clock.instant()));
    }
}
