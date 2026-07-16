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
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.identity.GenerationTaskIdGenerator;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.service.trace.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/** Coordinates the complete lifecycle of the heavy generation workflow. */
@Slf4j
@Component
@RequiredArgsConstructor
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
    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GenerationTraceService generationTraceService;
    private final GenerationExecutionContextService generationExecutionContextService;
    private final GenerationTaskIdGenerator generationTaskIdGenerator;

    /** Starts heavy generation with an execution envelope allocated by the task runtime. */
    public void startManaged(GenerationPipelineRequest pipelineRequest) {
        if (pipelineRequest.execution() == null) {
            throw new IllegalArgumentException("managed heavy generation requires a task execution envelope");
        }
        start(pipelineRequest);
    }

    /**
     * Starts heavy generation. Requests from the production orchestrator carry a preallocated
     * execution envelope; the legacy allocation path remains for isolated compatibility callers.
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
            preparation = heavyGenerationPreparationService.prepare(taskId, app, request.message());
            executionContext.assertCanContinue();
            assertConsistentTaskIdentity(taskId, preparation);
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
            startGenerationTask(app.getId(), loginUser, preparation, session, request);
            return new GenerationTaskResult(
                    taskId, pipelineRequest.modeDecision().route(), pipelineRequest.workspace(), session.asFlux());
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
            generationTaskLifecycleService.recordUserMessage(appId, loginUser.getId(), message);
            boolean lifecycleStarted = false;
            try {
                executionContext.assertCanContinue();
                generationTaskLifecycleService.startGeneration(
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
                if (lifecycleStarted) {
                    try {
                        generationTaskLifecycleService.completeGeneration(
                                preparation.taskId(),
                                appId,
                                GenerationTaskStatus.FAILED,
                                "generation_start_failed"
                        );
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
        runInitializationCleanupStep(taskId, "clear tool context", startupFailure,
                () -> generationToolExecutionContextService.clearContext(appId));
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
                () -> generationExecutionContextService.finish(taskId, outcome.status()));
    }

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

    private void startGenerationTask(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session,
                                     GenerationTaskRequest request) {
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
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
                        generationPerformanceMonitorService.startSpan(preparation.taskId(), "llm_generation", GenerationSpanCategory.MODEL);
                try {
                    heavyGenerationExecutionService.runGenerationWithAutoRepair(appId, loginUser, preparation, session);
                    generationSpan.success();
                } catch (Exception e) {
                    generationSpan.failed(e.getMessage());
                    throw e;
                }
                if (session.isCancelled()) {
                    finishCancelledGeneration(appId, session, preparation);
                    return;
                }
                if (preparation.requiresBuildValidation()) {
                    startBackgroundBuild(appId, loginUser, preparation, session, request);
                } else {
                    startBackgroundFinalization(appId, loginUser, preparation, session, request);
                }
            } catch (Exception e) {
                GenerationTerminalOutcome outcome = GenerationTerminalOutcome.resolve(session, e);
                if (outcome == GenerationTerminalOutcome.CANCELLED) {
                    log.info("重型生成任务已取消，appId: {}", appId);
                } else {
                    log.error("重型生成任务执行失败，appId: {}, outcome: {}", appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
                }
                completeHeavyTask(appId, request, preparation, session, outcome, e);
            } finally {
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void startBackgroundBuild(Long appId,
                                      User loginUser,
                                      GenerationPreparation preparation,
                                      GenerationSession session,
                                      GenerationTaskRequest request) {
        markGenerationStage(appId, preparation.taskId(), AppConstant.GENERATING_STAGE_BUILD,
                "代码生成完成，正在执行构建验证...");
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            GenerationTerminalOutcome outcome = GenerationTerminalOutcome.SUCCESS;
            Throwable failure = null;
            GenerationPerformanceMonitorService.SpanTimer buildSpan =
                    generationPerformanceMonitorService.startSpan(preparation.taskId(), "build_validation", GenerationSpanCategory.VALIDATION);
            try {
                session.throwIfCancelled();
                boolean buildSucceeded = heavyGenerationBuildValidationService.runWithAutoRepair(
                        appId, loginUser, preparation, session);
                if (buildSucceeded) {
                    buildSpan.success();
                    GenerationPerformanceMonitorService.SpanTimer finalizeSpan =
                            generationPerformanceMonitorService.startSpan(preparation.taskId(), "finalization", GenerationSpanCategory.FINALIZATION);
                    try {
                        session.throwIfCancelled();
                        heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                        heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
                        finalizeSpan.success();
                    } catch (Exception e) {
                        finalizeSpan.failed(e.getMessage());
                        throw e;
                    }
                } else {
                    outcome = GenerationTerminalOutcome.resolve(session, null);
                    buildSpan.failed("build validation failed");
                }
            } catch (Exception e) {
                failure = e;
                outcome = GenerationTerminalOutcome.resolve(session, e);
                buildSpan.failed(e.getMessage());
                if (outcome == GenerationTerminalOutcome.CANCELLED) {
                    log.info("后台构建任务已取消，appId: {}", appId);
                } else {
                    log.error("后台构建任务失败，appId: {}, outcome: {}", appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
                }
            } finally {
                completeHeavyTask(appId, request, preparation, session, outcome, failure);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void startBackgroundFinalization(Long appId,
                                             User loginUser,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             GenerationTaskRequest request) {
        markGenerationStage(appId, preparation.taskId(), AppConstant.GENERATING_STAGE_BUILD,
                "代码生成完成，正在整理生成结果...");
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            GenerationTerminalOutcome outcome = GenerationTerminalOutcome.SUCCESS;
            Throwable failure = null;
            GenerationPerformanceMonitorService.SpanTimer finalizeSpan =
                    generationPerformanceMonitorService.startSpan(preparation.taskId(), "finalization", GenerationSpanCategory.FINALIZATION);
            try {
                session.throwIfCancelled();
                heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
                finalizeSpan.success();
            } catch (Exception e) {
                failure = e;
                outcome = GenerationTerminalOutcome.resolve(session, e);
                finalizeSpan.failed(e.getMessage());
                if (outcome == GenerationTerminalOutcome.CANCELLED) {
                    log.info("后台收尾任务已取消，appId: {}", appId);
                } else {
                    log.error("后台收尾任务失败，appId: {}, outcome: {}", appId, outcome.status(), LogExceptionSanitizer.sanitize(e));
                }
            } finally {
                completeHeavyTask(appId, request, preparation, session, outcome, failure);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void finishCancelledGeneration(Long appId,
                                           GenerationSession session,
                                           GenerationPreparation preparation) {
        completeHeavyTask(appId, null, preparation, session, GenerationTerminalOutcome.CANCELLED, null);
    }

    private void completeHeavyTask(Long appId,
                                   GenerationTaskRequest request,
                                   GenerationPreparation preparation,
                                   GenerationSession session,
                                   GenerationTerminalOutcome outcome,
                                   Throwable failure) {
        if (session == null || !session.tryBeginCompletion()) {
            return;
        }
        GenerationTerminalOutcome resolvedOutcome = outcome == null
                ? GenerationTerminalOutcome.resolve(session, failure)
                : outcome;
        runTerminalStep("emit terminal stream event", preparation,
                () -> emitTerminalStreamEvent(appId, preparation, session, resolvedOutcome, failure));
        boolean lifecyclePersisted = runTerminalStep("persist lifecycle", preparation,
                () -> heavyGenerationSessionCompletionService.completeClaimed(
                        appId, session, preparation, resolvedOutcome));
        runTerminalStep("complete generation session", preparation, session::complete);
        if (!lifecyclePersisted) {
            runTerminalStep("fallback release app generation state", preparation,
                    () -> generationTaskLifecycleService.releaseGenerationState(
                            preparation.taskId(), appId));
        }
        runTerminalStep("finish performance task", preparation,
                () -> generationPerformanceMonitorService.finishTask(preparation.taskId(), resolvedOutcome.status()));
        runTerminalStep("retain generation session for replay", preparation,
                () -> generationSessionRegistry.retainForReplay(appId, session));
        runTerminalStep("clear tool context", preparation,
                () -> generationToolExecutionContextService.clearContext(appId));
        GenerationTaskRequest completionRequest = request != null ? request : session.taskRequest();
        if (completionRequest != null) {
            runTerminalStep("publish completion event", preparation,
                    () -> publishCompletion(completionRequest, preparation, resolvedOutcome));
        }
        runTerminalStep("finish execution context", preparation,
                () -> finishExecutionContext(preparation, resolvedOutcome.status()));
    }

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
        generationEventPublisher.publish(request, outcome.eventType(), outcome.eventMessage(), Map.of(
                "taskId", preparation.taskId(),
                "status", outcome.status(),
                "route", GenerationRoute.HEAVY_GENERATION
        ));
    }

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

    private void finishExecutionContext(GenerationPreparation preparation, String status) {
        if (preparation != null) {
            generationExecutionContextService.finish(preparation.taskId(), status);
        }
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        return heavyGenerationSessionCompletionService.orchestrationMode(preparation);
    }
}
