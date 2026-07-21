package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Read-only task query service with local realtime data, durable fallback and telemetry-derived ETA. */
@Slf4j
@Service
@RequiredArgsConstructor
public class GenerationTaskQueryService {

    private static final String RUNNING_STATUS = "running";
    private static final String CANCELLING_STATUS = "cancelling";

    private final GenerationSessionRegistry generationSessionRegistry;
    private final DurableGenerationTaskRepository durableRepository;
    private final GenerationTaskProgressEstimator progressEstimator;
    private final GenerationEventStream generationEventStream;
    private final AppPersistenceService appPersistenceService;
    private final TenantAuthorizationService tenantAuthorizationService;

    public GenerationTaskSnapshot get(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            return localSnapshot(session, safeFindDurableMetadata(taskId));
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        return durableSnapshot(task);
    }

    /** Finds the task a refreshed or second client should resume for an application. */
    public Optional<GenerationTaskSnapshot> findLatestNonTerminalForApp(Long appId, User actor) {
        requireActor(actor);
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid application id");
        }
        App app = appPersistenceService.findActiveById(appId);
        if (app == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Application does not exist");
        }
        requireTenantViewer(app.getTenantId(), actor);
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session != null && session.isActive()) {
            assertOwnedSession(session, actor);
            return Optional.of(localSnapshot(session, safeFindDurableMetadata(session.taskId())));
        }
        return durableRepository.findLatestNonTerminalByAppId(appId)
                .map(task -> {
                    assertOwnedTask(task, actor);
                    return durableSnapshot(task);
                });
    }

    public Flux<GenerationStreamEvent> events(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            return session.asFlux();
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        if (!task.terminal() || generationEventStream.available(taskId)) {
            return generationEventStream.stream(taskId);
        }
        throw eventStreamUnavailable();
    }

    /** Durable task API stream with replay cursor, explicit gaps and a sequenced completion marker. */
    public Flux<SequencedGenerationEvent> sequencedEvents(String taskId,
                                                          long afterSequence,
                                                          User actor) {
        requireActor(actor);
        if (afterSequence < 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Generation event cursor cannot be negative");
        }
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            if (!session.isActive() && !generationEventStream.available(taskId)) {
                throw eventStreamUnavailable();
            }
            return generationEventStream.stream(taskId, afterSequence);
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        if (!task.terminal() || generationEventStream.available(taskId)) {
            return generationEventStream.stream(taskId, afterSequence);
        }
        throw eventStreamUnavailable();
    }

    public Flux<GenerationStreamEvent> eventsForLatestNonTerminalAppTask(Long appId, User actor) {
        requireActor(actor);
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid application id");
        }
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session != null) {
            assertOwnedSession(session, actor);
            return session.asFlux();
        }
        return durableRepository.findLatestNonTerminalByAppId(appId)
                .map(task -> {
                    assertOwnedTask(task, actor);
                    return generationEventStream.stream(task.taskId());
                })
                .orElseGet(Flux::empty);
    }

    GenerationSession localSession(String taskId) {
        return generationSessionRegistry.getByTaskId(taskId);
    }

    DurableGenerationTaskRecord requireDurableTask(String taskId) {
        return durableRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist"));
    }

    private GenerationTaskSnapshot localSnapshot(GenerationSession session,
                                                 DurableGenerationTaskRecord durableMetadata) {
        GenerationExecutionContext executionContext = session.executionContext();
        if (executionContext == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Generation execution context does not exist");
        }
        GenerationExecutionSnapshot execution = executionContext.snapshot();
        String status = durableMetadata != null
                && durableMetadata.status() == GenerationTaskStatus.WAITING_APPROVAL
                ? durableMetadata.status().getValue()
                : execution.terminalStatus() != null
                ? execution.terminalStatus()
                : execution.cancelled() ? CANCELLING_STATUS : RUNNING_STATUS;
        String route = session.route() != null
                ? session.route()
                : durableMetadata == null ? null : durableMetadata.route();
        Instant submittedAt = durableMetadata == null
                ? execution.startedAt()
                : durableMetadata.submittedAt();
        Instant deadlineAt = durableMetadata != null && durableMetadata.deadlineAt() != null
                ? durableMetadata.deadlineAt()
                : execution.deadlineAt();
        String stage = durableMetadata == null ? null : durableMetadata.stage();
        String stageMessage = durableMetadata == null ? null : durableMetadata.stageMessage();
        GenerationTaskProgressEstimate progress = progressEstimator.estimate(
                route, status, submittedAt, deadlineAt, stage);
        return new GenerationTaskSnapshot(
                execution.taskId(), execution.appId(), execution.userId(), route, status,
                stage, stageMessage, submittedAt, deadlineAt, execution.cancelled(),
                execution.cancellationReason(), execution.usages(), execution.limits(), progress
        );
    }

    private GenerationTaskSnapshot durableSnapshot(DurableGenerationTaskRecord task) {
        String status = task.status().getValue();
        GenerationTaskProgressEstimate progress = progressEstimator.estimate(
                task.route(), status, task.submittedAt(), task.deadlineAt(), task.stage());
        return new GenerationTaskSnapshot(
                task.taskId(), task.appId(), task.userId(), task.route(), status,
                task.stage(), task.stageMessage(), task.submittedAt(), task.deadlineAt(),
                task.cancellationRequested(), task.cancellationReason(), Map.of(), Map.of(), progress
        );
    }

    private DurableGenerationTaskRecord safeFindDurableMetadata(String taskId) {
        try {
            return durableRepository.findByTaskId(taskId).orElse(null);
        } catch (RuntimeException failure) {
            log.warn("Durable task metadata unavailable for local task status, taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(failure));
            return null;
        }
    }

    private void assertOwnedSession(GenerationSession session, User actor) {
        GenerationTaskRequest taskRequest = session.taskRequest();
        if (taskRequest == null || taskRequest.app() == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "No permission to access this generation task");
        }
        requireTenantViewer(taskRequest.app().getTenantId(), actor);
    }

    private void assertOwnedTask(DurableGenerationTaskRecord task, User actor) {
        requireTenantViewer(task.tenantId(), actor);
    }

    private void requireTenantViewer(Long tenantId, User actor) {
        tenantAuthorizationService.requireRole(
                tenantId,
                actor.getId(),
                TenantRole.VIEWER,
                "No permission to access this generation task"
        );
    }

    private void requireActor(User actor) {
        if (actor == null || actor.getId() == null || actor.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "User is not logged in");
        }
    }

    private BusinessException eventStreamUnavailable() {
        return new BusinessException(
                ErrorCode.OPERATION_ERROR,
                "Generation task event stream is no longer available"
        );
    }
}
