package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.tool.GenerationApprovalRequiredException;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolContinuationState;
import com.rush.rushaicodemother.orchestration.tool.ToolApprovalRecord;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspaceService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceReleaseService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** 协调重型发电工作流程的完整生命周期。 */
@Slf4j
@Component
@RequiredArgsConstructor(onConstructor_ = @Autowired)
public class HeavyGenerationCoordinator {

    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationSessionRegistry generationSessionRegistry;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final HeavyGenerationBuildValidationService heavyGenerationBuildValidationService;
    private final HeavyGenerationExecutionService heavyGenerationExecutionService;
    private final HeavyGenerationFailureRecoveryService heavyGenerationFailureRecoveryService;
    private final HeavyGenerationFinalizationService heavyGenerationFinalizationService;
    private final HeavyGenerationPreparationService heavyGenerationPreparationService;
    private final HeavyGenerationSessionCompletionService heavyGenerationSessionCompletionService;
    private final GenerationTaskFinalizer generationTaskFinalizer;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService;
    private final GenerationTraceService generationTraceService;
    private final GenerationExecutionContextService generationExecutionContextService;
    private final GenerationTaskIdGenerator generationTaskIdGenerator;
    private final GenerationExecutionWorkspaceService executionWorkspaceService;
    private final GenerationWorkspaceReleaseService workspaceReleaseService;

    /** 用于测试和非托管调用者的兼容性构造函数早于 epoch 拥有的工作区。 */
    public HeavyGenerationCoordinator(
            GenerationEventPublisher generationEventPublisher,
            GenerationSessionRegistry generationSessionRegistry,
            GenerationPerformanceMonitorService generationPerformanceMonitorService,
            HeavyGenerationBuildValidationService heavyGenerationBuildValidationService,
            HeavyGenerationExecutionService heavyGenerationExecutionService,
            HeavyGenerationFailureRecoveryService heavyGenerationFailureRecoveryService,
            HeavyGenerationFinalizationService heavyGenerationFinalizationService,
            HeavyGenerationPreparationService heavyGenerationPreparationService,
            HeavyGenerationSessionCompletionService heavyGenerationSessionCompletionService,
            GenerationTaskFinalizer generationTaskFinalizer,
            GenerationTaskLifecycleService generationTaskLifecycleService,
            GenerationToolExecutionContextService generationToolExecutionContextService,
            GenerationTaskRuntimeLifecycleService generationTaskRuntimeLifecycleService,
            GenerationTraceService generationTraceService,
            GenerationExecutionContextService generationExecutionContextService,
            GenerationTaskIdGenerator generationTaskIdGenerator
    ) {
        this(
                generationEventPublisher,
                generationSessionRegistry,
                generationPerformanceMonitorService,
                heavyGenerationBuildValidationService,
                heavyGenerationExecutionService,
                heavyGenerationFailureRecoveryService,
                heavyGenerationFinalizationService,
                heavyGenerationPreparationService,
                heavyGenerationSessionCompletionService,
                generationTaskFinalizer,
                generationTaskLifecycleService,
                generationToolExecutionContextService,
                generationTaskRuntimeLifecycleService,
                generationTraceService,
                generationExecutionContextService,
                generationTaskIdGenerator,
                null,
                null
        );
    }

    /** 使用任务运行时分配的执行包络开始大量生成。 */
    public void startManaged(GenerationPipelineRequest pipelineRequest) {
        if (pipelineRequest.execution() == null) {
            throw new IllegalArgumentException("managed heavy generation requires a task execution envelope");
        }
        start(pipelineRequest);
    }

    /**
     * 开始大量生成。来自生产协调器的请求带有预分配的
     * 执行信封；遗留分配路径仍然供隔离的兼容性调用者使用。
     */
    public GenerationTaskResult start(GenerationPipelineRequest pipelineRequest) {
        GenerationTaskRequest request = pipelineRequest.taskRequest();
        App app = request.app();
        User loginUser = request.loginUser();
        CodeGenTypeEnum codeGenType = pipelineRequest.codeGenType();
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用重型生成路径", Map.of(
                "mode", pipelineRequest.modeDecision().mode().name(),
                "route", pipelineRequest.modeDecision().route(),
                "reason", pipelineRequest.modeDecision().reason(),
                "fallbackReason", pipelineRequest.modeDecision().fallbackReason(),
                "codeGenType", codeGenType.getValue()
        ));

        GenerationTaskExecution managedExecution = pipelineRequest.execution();
        String taskId = managedExecution == null
                ? generationTaskIdGenerator.nextId()
                : managedExecution.taskId();
        Instant prepareStartedAt = Instant.now();
        GenerationExecutionContext executionContext = managedExecution == null
                ? null
                : managedExecution.executionContext();
        GenerationPreparation preparation = null;
        GenerationSession session = null;
        boolean performanceStarted = false;
        boolean preparationRecorded = false;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (executionContext == null) {
                executionContext = reserveExecutionContext(taskId, app.getId(), loginUser.getId());
            } else {
                executionContext.assertCanContinue();
            }
            generationPerformanceMonitorService.startTask(
                    taskId,
                    app.getId(),
                    loginUser.getId(),
                    pipelineRequest.modeDecision().route(),
                    codeGenType.getValue(),
                    prepareStartedAt,
                    pipelineRequest.modeDecision()
            );
            performanceStarted = true;
            preparation = request.planningVariant() == GenerationPlanningVariant.CURRENT_DAG
                    ? heavyGenerationPreparationService.prepare(taskId, app, request.message())
                    : heavyGenerationPreparationService.prepare(
                            taskId, app, request.message(), request.planningVariant());
            executionContext.assertCanContinue();
            assertConsistentTaskIdentity(taskId, preparation);
            GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.resolve(
                    pipelineRequest.executionPlan(),
                    pipelineRequest.modeDecision().expectedValidationLevel()
            );
            preparation = verificationPolicy.enforceValidationFloor(preparation);
            generationPerformanceMonitorService.recordSpan(
                    taskId,
                    "heavy_prepare",
                    "success",
                    Duration.between(prepareStartedAt, Instant.now()),
                    ""
            );
            preparationRecorded = true;
            session = openGenerationSession(
                    app.getId(), request.message(), loginUser, preparation, request, executionContext,
                    managedExecution == null ? null : managedExecution.session());
            session.bindExecutionPlan(pipelineRequest.executionPlan());
            if (executionWorkspaceService != null
                    && managedExecution != null && managedExecution.executionFence() != null) {
                GenerationExecutionWorkspace targetWorkspace = executionWorkspaceService.require(
                        managedExecution.executionFence(), app.getId(), preparation.targetType());
                session.bindExecutionWorkspace(targetWorkspace);
                generationToolExecutionContextService.bindExecutionFence(
                        app.getId(), preparation.taskId(), managedExecution.executionFence());
                generationToolExecutionContextService.bindWorkspace(
                        app.getId(), preparation.taskId(), targetWorkspace.workspace(),
                        managedExecution.executionFence());
            }
            startGenerationTask(app.getId(), loginUser, preparation, session, request);
            return new GenerationTaskResult(
                    new GenerationTaskSubmissionReceipt(
                            taskId,
                            app.getId(),
                            pipelineRequest.modeDecision().route(),
                            GenerationTaskStatus.RUNNING,
                            executionContext.startedAt(),
                            executionContext.deadlineAt()
                    ),
                    session.executionWorkspace() == null
                            ? pipelineRequest.workspace()
                            : session.executionWorkspace().workspace(),
                    session.asFlux());
        } catch (RuntimeException startupFailure) {
            if (session != null && preparation != null) {
                completeHeavyTask(
                        app.getId(),
                        request,
                        preparation,
                        session,
                        GenerationTerminalOutcome.resolve(session, startupFailure),
                        startupFailure
                );
            } else if (executionContext != null) {
                cleanupInitializationFailure(
                        taskId,
                        app.getId(),
                        request,
                        pipelineRequest,
                        performanceStarted,
                        preparationRecorded,
                        prepareStartedAt,
                        startupFailure
                );
            }
            throw startupFailure;
        }
    }

    public Flux<GenerationStreamEvent> getStream(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        ThrowUtils.throwIf(session == null, ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        return session.asFlux();
    }

    /**
 * 停止重型生成协调器。
 *
 * @param appId 应用编号
 */
    public void stop(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        ThrowUtils.throwIf(session == null || !session.isActive(), ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        session.cancel();
        generationExecutionContextService.cancelByAppId(appId, "user_requested");
        GenerationPreparation preparation = session.preparation();
        if (preparation != null) {
            completeHeavyTask(appId, null, preparation, session, GenerationTerminalOutcome.CANCELLED, null);
        }
    }

    /**
 * 处理{@code resume}执行后工具决策。
 *
 * @param approval 审批
 * @param state 状态
 * @param session 会话
 */
    public void resumeAfterToolDecision(ToolApprovalRecord approval,
                                        GenerationToolContinuationState state,
                                        GenerationSession session) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (approval == null || state == null || session == null
                || session.taskRequest() == null || session.taskRequest().loginUser() == null
                || !Objects.equals(approval.taskId(), state.taskId())
                || !Objects.equals(state.taskId(), session.taskId())) {
            throw new IllegalArgumentException("tool continuation state is inconsistent");
        }
        Long appId = state.appId();
        User loginUser = session.taskRequest().loginUser();
        GenerationPreparation preparation = state.preparation();
        GenerationTaskRequest request = session.taskRequest();
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                        .userId(loginUser.getId().toString())
                        .appId(appId.toString())
                        .taskId(state.taskId())
                        .build()
        );
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.throwIfCancelled();
            markGenerationStage(
                    appId, state.taskId(), preparation.generatingStage(),
                    "审批已处理，正在从原工具调用继续生成...");
            GenerationPerformanceMonitorService.SpanTimer continuationSpan =
                    generationPerformanceMonitorService.startSpan(
                            state.taskId(), "tool_approval_continuation", GenerationSpanCategory.MODEL);
            try {
                heavyGenerationExecutionService.continueGenerationAfterDecision(
                        appId, loginUser, approval, state, session);
                continuationSpan.success();
            } catch (GenerationApprovalRequiredException approvalRequired) {
                continuationSpan.close("suspended", "approval_required");
                throw approvalRequired;
            } catch (Exception continuationFailure) {
                continuationSpan.failed(continuationFailure.getMessage());
                throw continuationFailure;
            }
            if (session.isCancelled()) {
                finishCancelledGeneration(appId, session, preparation);
            } else if (verificationPolicy(session).requiresBuildValidation(preparation)) {
                runBuildValidation(appId, loginUser, preparation, session, request);
            } else {
                runFinalization(appId, preparation, session, request);
            }
        } catch (GenerationApprovalRequiredException approvalRequired) {
            suspendForApproval(preparation, session, approvalRequired);
        } catch (Exception failure) {
            GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, failure);
            completeHeavyTask(appId, request, preparation, session, outcome, failure);
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    /**
 * 处理超时{@code Waiting}工具审批。
 *
 * @param state 状态
 * @param session 会话
 */
    public void timeoutWaitingToolApproval(GenerationToolContinuationState state,
                                           GenerationSession session) {
        if (state == null || session == null || !Objects.equals(state.taskId(), session.taskId())) {
            throw new IllegalArgumentException("waiting approval timeout state is inconsistent");
        }
        completeHeavyTask(
                state.appId(),
                session.taskRequest(),
                state.preparation(),
                session,
                GenerationTerminalOutcome.DEADLINE_EXCEEDED,
                new com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException(
                        state.taskId())
        );
    }

    /** 打开并初始化生成会话。 */
    private GenerationSession openGenerationSession(Long appId,
                                                     String message,
                                                     User loginUser,
                                                     GenerationPreparation preparation,
                                                     GenerationTaskRequest request,
                                                     GenerationExecutionContext executionContext,
                                                     GenerationSession preRegisteredSession) {
        synchronized (generationSessionRegistry.lock(appId)) {
            if (preRegisteredSession == null) {
                generationSessionRegistry.assertNoActiveSession(appId);
                resetResidualGenerationState(appId);
            } else {
                GenerationSession registeredSession = generationSessionRegistry.get(appId);
                if (registeredSession != preRegisteredSession) {
                    throw new GenerationExecutionPolicyException(
                            "预注册的生成会话与当前应用不匹配");
                }
            }
            boolean lifecycleStarted = false;
            try {
                executionContext.assertCanContinue();
                generationTaskLifecycleService.startOrTransitionGeneration(
                        preparation.taskId(),
                        appId,
                        loginUser.getId(),
                        preparation.originalType(),
                        preparation.targetType(),
                        message,
                        preparation.enhancedMessage(),
                        preparation.requiresBuildValidation(),
                        preparation.qualityGateLevel(),
                        orchestrationMode(preparation),
                        preparation.generatingStage()
                );
                lifecycleStarted = true;
                updateGenerationPhase(
                        appId, preparation.taskId(), AppConstant.GENERATING_STAGE_AGENT,
                        "智能体正在分析需求并规划生成策略...");
                GenerationSession session = preRegisteredSession == null
                        ? new GenerationSession(preparation, executionContext)
                        : preRegisteredSession;
                session.bindPreparation(preparation);
                session.bindTaskRequest(request);
                session.bindTraceContext(generationTraceService, appId, loginUser.getId());
                if (preRegisteredSession == null) {
                    generationSessionRegistry.put(appId, session);
                }
                return session;
            } catch (RuntimeException startFailure) {
                if (preRegisteredSession == null) {
                    generationSessionRegistry.remove(appId);
                }
                if (lifecycleStarted && preRegisteredSession == null) {
                    try {
                        generationTaskFinalizer.finalizeManaged(GenerationFinalizationCommand.of(
                                preparation.taskId(),
                                appId,
                                executionContext.executionFence(),
                                GenerationTaskStatus.FAILED,
                                "generation_start_failed",
                                null,
                                null
                        ));
                    } catch (RuntimeException cleanupFailure) {
                        startFailure.addSuppressed(cleanupFailure);
                    }
                }
                throw startFailure;
            }
        }
    }

    private GenerationExecutionContext reserveExecutionContext(String taskId, Long appId, Long userId) {
        synchronized (generationSessionRegistry.lock(appId)) {
            resetResidualGenerationState(appId);
            generationSessionRegistry.assertNoActiveSession(appId);
            return generationExecutionContextService.start(taskId, appId, userId);
        }
    }

    private void assertConsistentTaskIdentity(String taskId, GenerationPreparation preparation) {
        if (preparation == null || !taskId.equals(preparation.taskId())) {
            throw new GenerationExecutionPolicyException("生成准备阶段返回了不一致的任务标识");
        }
    }

    /** 清理{@code Initialization}失败及其关联资源。 */
    private void cleanupInitializationFailure(String taskId,
                                              Long appId,
                                              GenerationTaskRequest request,
                                              GenerationPipelineRequest pipelineRequest,
                                              boolean performanceStarted,
                                              boolean preparationRecorded,
                                              Instant prepareStartedAt,
                                              RuntimeException startupFailure) {
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(null, startupFailure);
        if (performanceStarted && !preparationRecorded) {
            runInitializationCleanupStep(taskId, "record failed preparation span", startupFailure,
                    () -> generationPerformanceMonitorService.recordSpan(
                            taskId,
                            "heavy_prepare",
                            "failed",
                            Duration.between(prepareStartedAt, Instant.now()),
                            startupFailure.getClass().getSimpleName()
                    ));
        }
        GenerationExecutionFence executionFence = pipelineRequest.execution() == null
                ? null
                : pipelineRequest.execution().executionFence();
        runInitializationCleanupStep(taskId, "clear tool context", startupFailure,
                () -> clearToolExecutionContext(appId, taskId, executionFence));
        if (pipelineRequest.execution() != null) {
            return;
        }
        if (performanceStarted) {
            runInitializationCleanupStep(taskId, "finish performance task", startupFailure,
                    () -> generationPerformanceMonitorService.finishTask(taskId, outcome.status()));
        }
        runInitializationCleanupStep(taskId, "publish initialization failure", startupFailure,
                () -> generationEventPublisher.publishSafely(
                        request,
                        outcome.eventType(),
                        outcome.eventMessage(),
                        Map.of(
                                "taskId", taskId,
                                "status", outcome.status(),
                                "route", pipelineRequest.modeDecision().route(),
                                "phase", "preparation"
                        )
                ));
        runInitializationCleanupStep(taskId, "finish execution context", startupFailure,
                () -> finishExecutionContext(taskId, executionFence, outcome.status()));
    }

    /** 运行{@code Initialization}{@code Cleanup}{@code Step}处理流程。 */
    private void runInitializationCleanupStep(String taskId,
                                              String step,
                                              RuntimeException startupFailure,
                                              Runnable action) {
        try {
            action.run();
        } catch (RuntimeException cleanupFailure) {
            startupFailure.addSuppressed(cleanupFailure);
            log.error("生成任务初始化清理步骤失败，taskId: {}, step: {}",
                    taskId, step, LogExceptionSanitizer.sanitize(cleanupFailure));
        }
    }

    /** 启动生成任务。 */
    private void startGenerationTask(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session,
                                     GenerationTaskRequest request) {
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                        .userId(loginUser.getId().toString())
                        .appId(appId.toString())
                        .taskId(preparation.taskId())
                        .build()
        );
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.throwIfCancelled();
            generationEventPublisher.publish(request, GenerationEventType.GENERATION_START, "重型生成任务开始", Map.of(
                    "taskId", preparation.taskId(),
                    "route", GenerationRoute.HEAVY_GENERATION
            ));
            preparation.events().forEach(session::emit);
            markGenerationStage(appId, preparation.taskId(), preparation.generatingStage(),
                    "智能体编排完成，正在生成项目代码...");
            GenerationPerformanceMonitorService.SpanTimer generationSpan =
                    generationPerformanceMonitorService.startSpan(
                            preparation.taskId(), "llm_generation", GenerationSpanCategory.MODEL);
            try {
                heavyGenerationExecutionService.runGenerationWithAutoRepair(appId, loginUser, preparation, session);
                generationSpan.success();
            } catch (GenerationApprovalRequiredException approvalRequired) {
                generationSpan.close("suspended", "approval_required");
                throw approvalRequired;
            } catch (Exception e) {
                generationSpan.failed(e.getMessage());
                throw e;
            }
            if (session.isCancelled()) {
                finishCancelledGeneration(appId, session, preparation);
                return;
            }
            if (verificationPolicy(session).requiresBuildValidation(preparation)) {
                runBuildValidation(appId, loginUser, preparation, session, request);
            } else {
                runFinalization(appId, preparation, session, request);
            }
        } catch (GenerationApprovalRequiredException approvalRequired) {
            suspendForApproval(preparation, session, approvalRequired);
        } catch (Exception e) {
            GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, e);
            if (outcome == GenerationTerminalOutcome.CANCELLED) {
                log.info("重型生成任务已取消，appId: {}", appId);
            } else {
                log.error("重型生成任务执行失败，appId: {}, outcome: {}",
                        appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
            }
            completeHeavyTask(appId, request, preparation, session, outcome, e);
        } finally {
            MonitorContextHolder.clearContext();
        }
    }

    /** 处理{@code suspend}{@code For}审批。 */
    private void suspendForApproval(GenerationPreparation preparation,
                                    GenerationSession session,
                                    GenerationApprovalRequiredException approvalRequired) {
        boolean suspended = generationTaskRuntimeLifecycleService.suspendForApproval(
                requireExecutionFence(session),
                "approval_required:" + approvalRequired.action().value());
        if (!suspended) {
            throw new GenerationExecutionPolicyException(
                    "generation task could not enter waiting approval state");
        }
        session.emit(GenerationStreamEvent.agentEvent("", Map.of(
                "agent", "PermissionPolicy",
                "stage", "approval",
                "status", "waiting_approval",
                "summary", "Generation is paused until the project owner decides",
                "taskId", preparation.taskId(),
                "action", approvalRequired.action().value(),
                "approvalId", approvalRequired.approvalId()
        )));
    }

    private com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence
    requireExecutionFence(GenerationSession session) {
        if (session == null || session.executionContext() == null
                || session.executionContext().executionFence() == null) {
            throw new GenerationExecutionPolicyException(
                    "durable generation execution fence is required for approval suspension");
        }
        return session.executionContext().executionFence();
    }

    private GenerationVerificationPolicy verificationPolicy(GenerationSession session) {
        if (session == null || session.executionPlan() == null) {
            return GenerationVerificationPolicy.legacy();
        }
        return GenerationVerificationPolicy.planned(session.executionPlan().validationGraph());
    }
    /** 运行构建校验处理流程。 */
    private void runBuildValidation(Long appId,
                                    User loginUser,
                                    GenerationPreparation preparation,
                                    GenerationSession session,
                                    GenerationTaskRequest request) {
        markGenerationStage(appId, preparation.taskId(), AppConstant.GENERATING_STAGE_BUILD,
                "代码生成完成，正在执行构建验证...");
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.SUCCESS;
        Throwable failure = null;
        GenerationPerformanceMonitorService.SpanTimer buildSpan =
                generationPerformanceMonitorService.startSpan(
                        preparation.taskId(), "build_validation", GenerationSpanCategory.VALIDATION);
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            session.throwIfCancelled();
            boolean buildSucceeded = heavyGenerationBuildValidationService.runWithAutoRepair(
                    appId,
                    loginUser,
                    preparation,
                    session,
                    verificationPolicy(session)
            );
            if (buildSucceeded) {
                buildSpan.success();
                runFinalizationSteps(appId, preparation, session);
            } else {
                outcome = GenerationTerminalOutcome.resolve(session, null);
                buildSpan.failed("build validation failed");
            }
        } catch (Exception e) {
            failure = e;
            outcome = GenerationTerminalOutcome.resolve(session, e);
            buildSpan.failed(e.getMessage());
            if (outcome == GenerationTerminalOutcome.CANCELLED) {
                log.info("构建任务已取消，appId: {}", appId);
            } else {
                log.error("构建任务失败，appId: {}, outcome: {}",
                        appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
            }
        } finally {
            completeHeavyTask(appId, request, preparation, session, outcome, failure);
        }
    }

    /** 运行{@code Finalization}处理流程。 */
    private void runFinalization(Long appId,
                                 GenerationPreparation preparation,
                                 GenerationSession session,
                                 GenerationTaskRequest request) {
        markGenerationStage(appId, preparation.taskId(), AppConstant.GENERATING_STAGE_BUILD,
                "代码生成完成，正在整理生成结果...");
        GenerationTerminalOutcome outcome = GenerationTerminalOutcome.SUCCESS;
        Throwable failure = null;
        try {
            runFinalizationSteps(appId, preparation, session);
        } catch (Exception e) {
            failure = e;
            outcome = GenerationTerminalOutcome.resolve(session, e);
            if (outcome == GenerationTerminalOutcome.CANCELLED) {
                log.info("收尾任务已取消，appId: {}", appId);
            } else {
                log.error("收尾任务失败，appId: {}, outcome: {}",
                        appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
            }
        } finally {
            completeHeavyTask(appId, request, preparation, session, outcome, failure);
        }
    }

    /** 运行{@code Finalization}{@code Steps}处理流程。 */
    private void runFinalizationSteps(Long appId,
                                      GenerationPreparation preparation,
                                      GenerationSession session) {
        GenerationPerformanceMonitorService.SpanTimer finalizeSpan =
                generationPerformanceMonitorService.startSpan(
                        preparation.taskId(), "finalization", GenerationSpanCategory.FINALIZATION);
        try {
            session.throwIfCancelled();
            heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
            heavyGenerationFinalizationService.requireCompletionEvidence(preparation, session);
            heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
            if (workspaceReleaseService != null) {
                GenerationFinalizationCommand terminalIntent =
                        heavyGenerationSessionCompletionService.publishedSuccessCommand(
                                appId, session, preparation);
                workspaceReleaseService.releaseVerified(
                        session, preparation.targetType(), terminalIntent);
            }
            finalizeSpan.success();
        } catch (Exception e) {
            finalizeSpan.failed(e.getMessage());
            throw e;
        }
    }

    private void finishCancelledGeneration(Long appId,
                                           GenerationSession session,
                                           GenerationPreparation preparation) {
        completeHeavyTask(appId, null, preparation, session, GenerationTerminalOutcome.CANCELLED, null);
    }

    /** 完成重型任务并持久化终态。 */
    private void completeHeavyTask(Long appId,
                                   GenerationTaskRequest request,
                                   GenerationPreparation preparation,
                                   GenerationSession session,
                                   GenerationTerminalOutcome outcome,
                                   Throwable failure) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (session == null || !session.tryBeginCompletion()) {
            return;
        }
        GenerationTerminalOutcome resolvedOutcome = outcome == null
                ? GenerationTerminalOutcome.resolve(session, failure)
                : outcome;
        boolean lifecyclePersisted = runTerminalStep("persist lifecycle", preparation,
                () -> heavyGenerationSessionCompletionService.completeClaimed(
                        appId, session, preparation, resolvedOutcome));
        if (!lifecyclePersisted) {
            log.error("生成任务终态尚未提交，保留运行时状态等待恢复，taskId: {}",
                    preparation == null ? null : preparation.taskId());
            return;
        }
        runTerminalStep("emit terminal stream event", preparation,
                () -> emitTerminalStreamEvent(appId, preparation, session, resolvedOutcome, failure));
        runTerminalStep("complete generation session", preparation, session::complete);
        runTerminalStep("finish performance task", preparation,
                () -> generationPerformanceMonitorService.finishTask(preparation.taskId(), resolvedOutcome.status()));
        runTerminalStep("retain generation session for replay", preparation,
                () -> generationSessionRegistry.retainForReplay(appId, session));
        GenerationExecutionFence executionFence = session.executionContext() == null
                ? null
                : session.executionContext().executionFence();
        runTerminalStep("clear tool context", preparation,
                () -> clearToolExecutionContext(
                        appId, preparation == null ? null : preparation.taskId(), executionFence));
        GenerationTaskRequest completionRequest = request != null ? request : session.taskRequest();
        if (completionRequest != null) {
            runTerminalStep("publish completion event", preparation,
                    () -> publishCompletion(completionRequest, preparation, resolvedOutcome));
        }
        runTerminalStep("finish execution context", preparation,
                () -> finishExecutionContext(preparation, executionFence, resolvedOutcome.status()));
    }

    /** 发送{@code Terminal}流事件事件。 */
    private void emitTerminalStreamEvent(Long appId,
                                         GenerationPreparation preparation,
                                         GenerationSession session,
                                         GenerationTerminalOutcome outcome,
                                         Throwable failure) {
        if (outcome == GenerationTerminalOutcome.CANCELLED) {
            session.emitStopped();
            return;
        }
        if (failure == null || outcome == GenerationTerminalOutcome.SUCCESS) {
            return;
        }
        GenerationExecutionPolicyException policyException = findExecutionPolicyException(failure);
        if (policyException != null) {
            heavyGenerationFailureRecoveryService.emitExecutionPolicyError(
                    appId, preparation, session, policyException);
            return;
        }
        heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, failure);
    }

    /** 查找匹配的执行策略异常。 */
    private GenerationExecutionPolicyException findExecutionPolicyException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof GenerationExecutionPolicyException policyException) {
                return policyException;
            }
        }
        return null;
    }

    private void publishCompletion(GenerationTaskRequest request,
                                   GenerationPreparation preparation,
                                   GenerationTerminalOutcome outcome) {
        long epoch = preparation == null ? 0L : generationExecutionContextService
                .getExecutionFence(preparation.taskId())
                .map(GenerationExecutionFence::executionEpoch)
                .orElse(0L);
        generationEventPublisher.publishIdempotently(
                request, outcome.eventType(), outcome.eventMessage(), Map.of(
                "eventId", "terminal:" + preparation.taskId() + ":" + epoch,
                "taskId", preparation.taskId(),
                "status", outcome.status(),
                "route", GenerationRoute.HEAVY_GENERATION
        ));
    }

    /** 运行{@code Terminal}{@code Step}处理流程。 */
    private boolean runTerminalStep(String step,
                                    GenerationPreparation preparation,
                                    Runnable action) {
        try {
            action.run();
            return true;
        } catch (Exception e) {
            log.error("生成任务终态处理步骤失败，taskId: {}, step: {}",
                    preparation == null ? null : preparation.taskId(), step, LogExceptionSanitizer.sanitize(e));
            return false;
        }
    }

    private void resetResidualGenerationState(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session != null && !session.isActive()) {
            generationSessionRegistry.remove(appId, session);
        }
    }

    private void updateGenerationPhase(Long appId,
                                       String taskId,
                                       String generatingStage,
                                       String generatingMessage) {
        markGenerationStage(appId, taskId, generatingStage, generatingMessage);
    }

    private void markGenerationStage(Long appId,
                                     String taskId,
                                     String generatingStage,
                                     String generatingMessage) {
        generationTaskLifecycleService.updateGenerationStage(
                taskId, appId, generatingStage, generatingMessage);
    }

    private void clearToolExecutionContext(Long appId,
                                           String taskId,
                                           GenerationExecutionFence executionFence) {
        if (executionFence == null) {
            generationToolExecutionContextService.clearContext(appId, taskId);
            return;
        }
        generationToolExecutionContextService.clearContext(appId, taskId, executionFence);
    }

    private void finishExecutionContext(GenerationPreparation preparation,
                                        GenerationExecutionFence executionFence,
                                        String status) {
        if (preparation != null) {
            finishExecutionContext(preparation.taskId(), executionFence, status);
        }
    }

    private void finishExecutionContext(String taskId,
                                        GenerationExecutionFence executionFence,
                                        String status) {
        if (executionFence == null) {
            generationExecutionContextService.finish(taskId, status);
            return;
        }
        generationExecutionContextService.finishIfOwned(taskId, executionFence, status);
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        return heavyGenerationSessionCompletionService.orchestrationMode(preparation);
    }
}
