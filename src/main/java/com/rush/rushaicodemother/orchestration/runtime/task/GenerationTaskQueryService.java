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

/** 具有本地实时数据、持久回退和遥测派生的 ETA 的只读任务查询服务。 */
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

    /**
 * 获取指定资源。
 *
 * @param taskId 任务编号
 * @param actor 操作发起人
 * @return 方法执行结果
 */
    public GenerationTaskSnapshot get(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            DurableGenerationTaskRecord durableTask = safeFindDurableMetadata(taskId);
            if (durableTaskOverridesSession(durableTask, session)) {
                assertOwnedTask(durableTask, actor);
                return durableSnapshot(durableTask);
            }
            return localSnapshot(session, durableTask);
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        return durableSnapshot(task);
    }

    /** 查找刷新后的客户端或第二个客户端应为应用程序恢复的任务。 */
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
            DurableGenerationTaskRecord durableTask = safeFindDurableMetadata(session.taskId());
            if (durableTask != null && durableTask.terminal()) {
                assertOwnedTask(durableTask, actor);
                return Optional.empty();
            }
            return Optional.of(localSnapshot(session, durableTask));
        }
        return durableRepository.findLatestNonTerminalByAppId(appId)
                .map(task -> {
                    assertOwnedTask(task, actor);
                    return durableSnapshot(task);
                });
    }

    /**
 * 返回事件。
 *
 * @param taskId 任务编号
 * @param actor 操作发起人
 * @return 异步响应式处理结果
 */
    public Flux<GenerationStreamEvent> events(String taskId, User actor) {
        requireActor(actor);
        GenerationSession session = generationSessionRegistry.getByTaskId(taskId);
        if (session != null) {
            assertOwnedSession(session, actor);
            DurableGenerationTaskRecord durableTask = findAuthorizedDurableTask(taskId, actor);
            if (durableTask != null && durableTask.terminal()) {
                return DurableGenerationTerminalEventProjection.legacy(durableTask);
            }
            if (session.isActive()) {
                return session.asFlux();
            }
            if (durableTask == null) {
                throw taskNotFound();
            }
            boolean sharedStreamAvailable = generationEventStream.available(taskId);
            return sharedStreamAvailable
                    ? generationEventStream.stream(taskId) : session.asFlux();
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        if (task.terminal()) {
            return DurableGenerationTerminalEventProjection.legacy(task);
        }
        return generationEventStream.stream(taskId);
    }

    /** 具有重放光标、显式间隙和有序完成标记的持久任务 API 流。 */
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
            DurableGenerationTaskRecord durableTask = findAuthorizedDurableTask(taskId, actor);
            if (durableTask != null && durableTask.terminal()) {
                return DurableGenerationTerminalEventProjection.sequenced(
                        durableTask, afterSequence);
            }
            if (session.isActive()) {
                return generationEventStream.stream(taskId, afterSequence);
            }
            if (durableTask == null) {
                throw taskNotFound();
            }
            boolean sharedStreamAvailable = generationEventStream.available(taskId);
            if (sharedStreamAvailable) {
                return generationEventStream.stream(taskId, afterSequence);
            }
            throw eventStreamUnavailable();
        }
        DurableGenerationTaskRecord task = requireDurableTask(taskId);
        assertOwnedTask(task, actor);
        if (task.terminal()) {
            return DurableGenerationTerminalEventProjection.sequenced(task, afterSequence);
        }
        return generationEventStream.stream(taskId, afterSequence);
    }

    /**
 * 返回事件{@code For}{@code Latest}{@code Non}{@code Terminal}应用任务。
 *
 * @param appId 应用编号
 * @param actor 操作发起人
 * @return 异步响应式处理结果
 */
    public Flux<GenerationStreamEvent> eventsForLatestNonTerminalAppTask(Long appId, User actor) {
        requireActor(actor);
        if (appId == null || appId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "Invalid application id");
        }
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session != null) {
            assertOwnedSession(session, actor);
            DurableGenerationTaskRecord durableTask = findAuthorizedDurableTaskForSession(
                    session, actor);
            if (durableTask != null && durableTask.terminal()) {
                return DurableGenerationTerminalEventProjection.legacy(durableTask);
            }
            if (session.isActive()) {
                return session.asFlux();
            }
            if (durableTask != null) {
                return generationEventStream.stream(durableTask.taskId());
            }
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
                .orElseThrow(this::taskNotFound);
    }

    private DurableGenerationTaskRecord findAuthorizedDurableTask(String taskId, User actor) {
        DurableGenerationTaskRecord durableTask = durableRepository.findByTaskId(taskId).orElse(null);
        if (durableTask != null) {
            assertOwnedTask(durableTask, actor);
        }
        return durableTask;
    }

    /** 查询本地会话对应的持久任务；遗留会话缺少 taskId 时不访问仓储。 */
    private DurableGenerationTaskRecord findAuthorizedDurableTaskForSession(
            GenerationSession session,
            User actor
    ) {
        String taskId = session == null ? null : session.taskId();
        if (taskId == null || taskId.isBlank()) {
            return null;
        }
        return findAuthorizedDurableTask(taskId, actor);
    }

    /**
     * 判断持久任务事实是否应覆盖本地会话。
     *
     * <p>终态一经事务提交即为权威事实；非终态时，仅当本地会话已经失活，
     * 才回落持久快照。这样既保留活跃 worker 的实时预算/进度，又防止清理延迟或
     * 跨节点接管期间的旧内存对象遮蔽数据库真相。</p>
     */
    private boolean durableTaskOverridesSession(
            DurableGenerationTaskRecord durableTask,
            GenerationSession session
    ) {
        return durableTask != null
                && (durableTask.terminal() || session == null || !session.isActive());
    }

    /** 返回{@code local}快照。 */
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
                task.cancellationRequested(), task.cancellationReason(), Map.of(), Map.of(), progress,
                task.deliveryReceipt()
        );
    }

    /** 返回安全{@code Find}持久元数据。 */
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

    private BusinessException taskNotFound() {
        return new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist");
    }
}
