package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionFactory;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineExecutor;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationCompletionRequirements;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.tracing.GenerationTraceContextBridge;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceExecutionScope;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import com.rush.rushaicodemother.service.user.UserPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** 重建持久命令并将其接纳到有界本地执行运行时。 */
@Service
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class GenerationTaskCommandExecutionService {

    private final DurableGenerationTaskRepository repository;
    private final AppPersistenceService appPersistenceService;
    private final UserPersistenceService userPersistenceService;
    private final TenantAuthorizationService tenantAuthorizationService;
    private final GenerationTaskResourceProvisioningService resourceProvisioningService;
    private final GenerationWorkspaceService workspaceService;
    private final GenerationExecutionWorkspaceService executionWorkspaceService;
    private final GenerationWorkspaceExecutionScope workspaceExecutionScope;
    private final GenerationToolExecutionContextService toolExecutionContextService;
    private final GenerationExecutionContextService executionContextService;
    private final GenerationRuntimeProperties runtimeProperties;
    private final GenerationSessionFactory sessionFactory;
    private final GenerationSessionRegistry sessionRegistry;
    private final GenerationTaskExecutor taskExecutor;
    private final GenerationPipelineExecutor pipelineExecutor;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final GenerationTaskFinalizer generationTaskFinalizer;
    private final GenerationTraceContextBridge traceContextBridge;
    private final GenerationPerformanceMonitorService performanceMonitorService;

    /** 遗留测试和非托管调用者的兼容性构造函数。 */
    public GenerationTaskCommandExecutionService(
            DurableGenerationTaskRepository repository,
            AppPersistenceService appPersistenceService,
            UserPersistenceService userPersistenceService,
            TenantAuthorizationService tenantAuthorizationService,
            GenerationTaskResourceProvisioningService resourceProvisioningService,
            GenerationWorkspaceService workspaceService,
            GenerationExecutionContextService executionContextService,
            GenerationRuntimeProperties runtimeProperties,
            GenerationSessionFactory sessionFactory,
            GenerationSessionRegistry sessionRegistry,
            GenerationTaskExecutor taskExecutor,
            GenerationPipelineExecutor pipelineExecutor,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService,
            GenerationTaskFinalizer generationTaskFinalizer,
            GenerationTraceContextBridge traceContextBridge,
            GenerationPerformanceMonitorService performanceMonitorService
    ) {
        this(
                repository,
                appPersistenceService,
                userPersistenceService,
                tenantAuthorizationService,
                resourceProvisioningService,
                workspaceService,
                null,
                null,
                null,
                executionContextService,
                runtimeProperties,
                sessionFactory,
                sessionRegistry,
                taskExecutor,
                pipelineExecutor,
                runtimeLifecycleService,
                generationTaskFinalizer,
                traceContextBridge,
                performanceMonitorService
        );
    }

    /**
 * 返回调度。
 *
 * @param taskId 任务编号
 * @param completionCallback 完成回调
 * @return 生成任务命令执行
 */
    public GenerationTaskDispatchResult schedule(String taskId, Runnable completionCallback) {
        Runnable callback = completionCallback == null ? () -> { } : completionCallback;
        DurableGenerationTaskRecord task = repository.findByTaskId(taskId).orElse(null);
        if (task == null || task.terminal() || task.status() == GenerationTaskStatus.WAITING_APPROVAL) {
            return GenerationTaskDispatchResult.TERMINAL;
        }
        Instant now = Instant.now();
        if (task.cancellationRequested()) {
            generationTaskFinalizer.finalizeUnownedRuntime(taskId, GenerationTaskStatus.CANCELLED,
                    normalize(task.cancellationReason(), "user_requested"));
            return GenerationTaskDispatchResult.TERMINAL;
        }
        if (task.deadlineAt() != null && !task.deadlineAt().isAfter(now)) {
            generationTaskFinalizer.finalizeUnownedRuntime(taskId, GenerationTaskStatus.DEADLINE_EXCEEDED,
                    "deadline_exceeded_before_dispatch");
            return GenerationTaskDispatchResult.TERMINAL;
        }
        if (task.status() != GenerationTaskStatus.QUEUED) {
            return GenerationTaskDispatchResult.ALREADY_ACTIVE;
        }
        GenerationSession localSession = sessionRegistry.getByTaskId(taskId);
        if (localSession != null && localSession.isActive()) {
            return GenerationTaskDispatchResult.ALREADY_ACTIVE;
        }
        GenerationExecutionFence executionFence = runtimeLifecycleService.reserveQueued(taskId).orElse(null);
        if (executionFence == null) {
            DurableGenerationTaskRecord current = repository.findByTaskId(taskId).orElse(null);
            if (current == null || current.terminal() || current.status() == GenerationTaskStatus.WAITING_APPROVAL) {
                return GenerationTaskDispatchResult.TERMINAL;
            }
            if (current.status() == GenerationTaskStatus.RUNNING
                    || (current.leaseOwner() != null && current.leaseUntil() != null
                    && current.leaseUntil().isAfter(now))) {
                return GenerationTaskDispatchResult.ALREADY_ACTIVE;
            }
            return GenerationTaskDispatchResult.RETRY;
        }
        GenerationExecutionWorkspace executionWorkspace = null;
        GenerationExecutionContext executionContext = null;
        GenerationSession session = null;
        Long appId = task.appId();
        boolean toolContextBound = false;
        boolean sessionRegistered = false;
        boolean claimReleased = false;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            GenerationTaskCommand command = repository.findCommandByTaskId(taskId).orElse(null);
            if (command == null) {
                claimReleased = true;
                generationTaskFinalizer.finalizeOwnedRuntime(runtimeFinalization(
                        task, executionFence, "generation_runtime_command_missing"));
                return GenerationTaskDispatchResult.TERMINAL;
            }
            appId = command.appId();
            App app = appPersistenceService.findActiveById(command.appId());
            User user = userPersistenceService.findActiveById(command.userId());
            if (app == null
                    || user == null
                    || !Objects.equals(app.getTenantId(), task.tenantId())
                    || (command.tenantId() != null && !Objects.equals(command.tenantId(), task.tenantId()))) {
                claimReleased = true;
                generationTaskFinalizer.finalizeOwnedRuntime(runtimeFinalization(
                        task, executionFence, "generation_identity_no_longer_exists"));
                return GenerationTaskDispatchResult.TERMINAL;
            }
            try {
                tenantAuthorizationService.requireRole(
                        task.tenantId(), user.getId(), TenantRole.DEVELOPER,
                        "生成任务执行人已无当前租户的操作权限");
            } catch (BusinessException noLongerAuthorized) {
                claimReleased = true;
                generationTaskFinalizer.finalizeOwnedRuntime(runtimeFinalization(
                        task, executionFence, "generation_actor_no_longer_authorized"));
                return GenerationTaskDispatchResult.TERMINAL;
            }

            boolean duplicateAfterClaim;
            synchronized (sessionRegistry.lock(command.appId())) {
                GenerationSession existing = sessionRegistry.getByTaskId(taskId);
                duplicateAfterClaim = existing != null && existing.isActive();
                if (!duplicateAfterClaim) {
                    sessionRegistry.assertNoActiveSession(command.appId());
                }
            }
            if (duplicateAfterClaim) {
                claimReleased = true;
                runtimeLifecycleService.releaseClaimToQueue(
                        executionFence, "duplicate_local_session_after_claim");
                return GenerationTaskDispatchResult.ALREADY_ACTIVE;
            }

            // 在任何潜在的昂贵费用之前恢复并隔离截止日期/取消上下文
            // 文件系统物化。  大型项目副本不得在任务 SLA 之外运行。
            executionContext = restoreExecutionContext(command);
            synchronized (executionContext) {
                executionContext.bindExecutionFence(executionFence);
            }
            executionContext.assertCanContinue();
            performanceMonitorService.startTask(
                    taskId,
                    command.appId(),
                    command.userId(),
                    command.route(),
                    command.codeGenType().getValue(),
                    command.submittedAt(),
                    command.modeDecision()
            );

            boolean readOnly = command.mode() == GenerationMode.READ_ONLY;
            if (!readOnly) {
                resourceProvisioningService.provision(command, app, executionContext);
            }

            if (!readOnly && executionWorkspaceService != null) {
                GenerationPerformanceMonitorService.SpanTimer workspaceSpan =
                        performanceMonitorService.startSpan(
                                taskId,
                                "execution_workspace_materialization",
                                GenerationSpanCategory.WORKSPACE
                        );
                try {
                    executionWorkspace = executionWorkspaceService.register(
                            executionFence, app.getId(), command.codeGenType());
                    workspaceSpan.close(
                            "success",
                            "seededFromEpoch=" + (executionWorkspace == null
                                    ? "none"
                                    : Objects.toString(executionWorkspace.seededFromEpoch(), "canonical_or_empty"))
                    );
                } catch (RuntimeException | Error workspaceFailure) {
                    workspaceSpan.failed(workspaceFailure.getClass().getSimpleName());
                    throw workspaceFailure;
                }
            }
            if (!readOnly && executionWorkspaceService != null && toolExecutionContextService != null) {
                // 在工作时代存在之前，准备工作可能已经绑定了任务级上下文。
                // 现在将其固定，以便每个模型/工具回调都可以解析确切的围栏。
                toolContextBound = toolExecutionContextService.bindExecutionFenceIfPresent(
                        app.getId(), taskId, executionFence);
                if (toolContextBound && executionWorkspace != null) {
                    toolExecutionContextService.bindWorkspace(
                            app.getId(), taskId, executionWorkspace.workspace(), executionFence);
                }
            }
            GenerationWorkspace workspace = executionWorkspace == null
                    ? workspaceService.resolve(app, command.codeGenType())
                    : executionWorkspace.workspace();
            GenerationPipelineRequest request = command.restore(app, user, workspace);

            boolean duplicateAfterSetup;
            synchronized (sessionRegistry.lock(command.appId())) {
                GenerationSession existing = sessionRegistry.getByTaskId(taskId);
                duplicateAfterSetup = existing != null && existing.isActive();
                if (!duplicateAfterSetup) {
                    sessionRegistry.assertNoActiveSession(command.appId());
                    session = sessionFactory.create(null, executionContext);
                    if (executionWorkspace != null) {
                        session.bindExecutionWorkspace(executionWorkspace);
                    }
                    session.bindTaskRequest(request.taskRequest());
                    session.bindExecutionPlan(request.executionPlan());
                    session.recordRoute(command.route());
                    sessionRegistry.put(command.appId(), session);
                    sessionRegistered = true;
                }
            }
            if (duplicateAfterSetup) {
                cleanupDispatchResources(
                        appId, taskId, executionFence, executionWorkspace, executionContext,
                        null, false, toolContextBound, "duplicate_local_session_after_setup");
                claimReleased = true;
                runtimeLifecycleService.releaseClaimToQueue(
                        executionFence, "duplicate_local_session_after_setup");
                return GenerationTaskDispatchResult.ALREADY_ACTIVE;
            }

            GenerationExecutionContext admittedExecutionContext = executionContext;
            GenerationSession admittedSession = session;
            GenerationTaskExecution execution = new GenerationTaskExecution(
                    taskId, admittedSession, admittedExecutionContext, executionFence, command.submittedAt());
            GenerationPipelineRequest executableRequest = request.withExecution(execution);
            Instant workerQueueStartedAt = Instant.now();
            Runnable tracedTask = traceContextBridge.wrap(
                    command.traceContext(),
                    "generation.task.execute",
                    Map.of(
                            "generation.task.id", command.taskId(),
                            "generation.execution.epoch", String.valueOf(executionFence.executionEpoch()),
                            "generation.tenant.id", String.valueOf(task.tenantId()),
                            "generation.app.id", String.valueOf(command.appId()),
                            "generation.user.id", String.valueOf(command.userId()),
                            "generation.route", command.route()
                    ),
                    () -> runInExecutionWorkspace(executionFence, !readOnly, () -> {
                        recordWorkerQueueWait(task, admittedExecutionContext, workerQueueStartedAt);
                        try {
                            pipelineExecutor.execute(executableRequest);
                        } finally {
                            callback.run();
                        }
                    }));
            taskExecutor.execute(execution, tracedTask);
            return GenerationTaskDispatchResult.SCHEDULED;
        } catch (RuntimeException | Error admissionFailure) {
            cleanupDispatchResourcesSafely(
                    appId, taskId, executionFence, executionWorkspace, executionContext,
                    session, sessionRegistered, toolContextBound, "dispatch_rejected", admissionFailure);
            if (!claimReleased) {
                try {
                    runtimeLifecycleService.releaseClaimToQueue(
                            executionFence, "worker_dispatch_rejected");
                } catch (RuntimeException releaseFailure) {
                    admissionFailure.addSuppressed(releaseFailure);
                }
            }
            throw admissionFailure;
        }
    }

    /** 清理{@code Dispatch}{@code Resources}安全处理及其关联资源。 */
    private void cleanupDispatchResourcesSafely(Long appId,
                                                String taskId,
                                                GenerationExecutionFence executionFence,
                                                GenerationExecutionWorkspace executionWorkspace,
                                                GenerationExecutionContext executionContext,
                                                GenerationSession session,
                                                boolean sessionRegistered,
                                                boolean toolContextBound,
                                                String completionStatus,
                                                Throwable primaryFailure) {
        try {
            cleanupDispatchResources(
                    appId, taskId, executionFence, executionWorkspace, executionContext,
                    session, sessionRegistered, toolContextBound, completionStatus);
        } catch (RuntimeException | Error cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    /** 清理{@code Dispatch}{@code Resources}及其关联资源。 */
    private void cleanupDispatchResources(Long appId,
                                          String taskId,
                                          GenerationExecutionFence executionFence,
                                          GenerationExecutionWorkspace executionWorkspace,
                                          GenerationExecutionContext executionContext,
                                          GenerationSession session,
                                          boolean sessionRegistered,
                                          boolean toolContextBound,
                                          String completionStatus) {
        if (sessionRegistered && appId != null && session != null) {
            sessionRegistry.remove(appId, session);
        }
        if (session != null) {
            session.complete();
        }
        if (toolContextBound && toolExecutionContextService != null) {
            toolExecutionContextService.clearContext(appId, taskId, executionFence);
        }
        if (executionWorkspace != null && executionWorkspaceService != null) {
            executionWorkspaceService.clear(
                    executionFence,
                    appId,
                    GenerationExecutionWorkspaceService.CleanupPolicy.DELETE);
        }
        if (executionContext != null) {
            executionContextService.finishIfOwned(taskId, executionFence, completionStatus);
        }
    }

    private Void runInExecutionWorkspace(GenerationExecutionFence executionFence,
                                         boolean workspaceIsolationRequired,
                                         Runnable action) {
        if (!workspaceIsolationRequired || workspaceExecutionScope == null) {
            action.run();
            return null;
        }
        return workspaceExecutionScope.with(executionFence, () -> {
            action.run();
            return null;
        });
    }

    /** 返回恢复执行上下文。 */
    private GenerationExecutionContext restoreExecutionContext(GenerationTaskCommand command) {
        return executionContextService.getByTaskId(command.taskId())
                .orElseGet(() -> {
                    GenerationExecutionLimits limits = command.slaEnvelope() == null
                            ? runtimeProperties.toLimits()
                            : command.slaEnvelope().toLimits();
                    limits = limits.withCompletionRequirements(completionRequirements(command));
                    EnumMap<GenerationBudgetKind, Integer> limitSnapshot =
                            new EnumMap<>(GenerationBudgetKind.class);
                    for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
                        limitSnapshot.put(kind, limits.limit(kind));
                    }
                    return executionContextService.restore(new GenerationExecutionSnapshot(
                            command.taskId(), command.appId(), command.userId(),
                            command.submittedAt(), command.deadlineAt(),
                            command.slaEnvelope() == null ? "legacy-default" : command.slaEnvelope().profile(),
                            command.slaEnvelope() == null
                                    ? command.deadlineAt()
                                    : command.slaEnvelope().firstPreviewDeadline(command.submittedAt()),
                            null,
                            false, null, null,
                            command.preflightUsage().asBudgetUsages(), Map.copyOf(limitSnapshot)
                    ), limits);
                });
    }

    /** 冻结计划优先；升级前没有计划的任务按目标工程恢复旧 Heavy 完成行为。 */
    private GenerationCompletionRequirements completionRequirements(GenerationTaskCommand command) {
        if (command.executionPlan() != null) {
            return GenerationVerificationPolicy
                    .planned(command.executionPlan().validationGraph())
                    .completionRequirements(command.codeGenType());
        }
        return GenerationCompletionRequirements.legacy(command.codeGenType());
    }

    private String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private GenerationFinalizationCommand runtimeFinalization(
            DurableGenerationTaskRecord task,
            GenerationExecutionFence fence,
            String reason) {
        return GenerationFinalizationCommand.of(
                task.taskId(), task.appId(), fence, GenerationTaskStatus.FAILED,
                reason, null, null,
                GenerationDeliveryReceiptFactory.fromTerminal(
                        task.route(), GenerationTaskStatus.FAILED,
                        GenerationCompletionEvidenceSet.empty(), null));
    }

    /** 记录工作器{@code Queue}{@code Wait}相关指标或状态。 */
    private void recordWorkerQueueWait(DurableGenerationTaskRecord task,
                                       GenerationExecutionContext executionContext,
                                       Instant queuedAt) {
        Instant startedAt = Instant.now();
        String status = executionContext.isCancelled()
                ? "cancelled"
                : executionContext.isDeadlineExceeded() ? "deadline_exceeded" : "success";
        performanceMonitorService.recordSpan(
                task.taskId(),
                "worker_queue_wait",
                GenerationSpanCategory.QUEUE,
                status,
                nonNegativeDuration(queuedAt, startedAt),
                "attempt=" + Math.max(0, task.attempt())
        );
    }

    private Duration nonNegativeDuration(Instant startedAt, Instant endedAt) {
        if (startedAt == null || endedAt == null || endedAt.isBefore(startedAt)) {
            return Duration.ZERO;
        }
        return Duration.between(startedAt, endedAt);
    }
}
