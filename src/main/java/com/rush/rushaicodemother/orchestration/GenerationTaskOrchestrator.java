package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationBuildValidationService;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationExecutionService;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationFailureRecoveryService;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationFinalizationService;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationPreparationService;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationSessionCompletionService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.pipeline.HeavyGenerationPipeline;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationTaskOrchestrator {

    private final GenerationAppStateService generationAppStateService;
    private final GenerationEventPublisher generationEventPublisher;
    private final List<GenerationPipeline> generationPipelines;
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
    private final GenerationModeRouter generationModeRouter;
    private final GenerationWorkspaceService generationWorkspaceService;

    public GenerationTaskResult start(GenerationTaskRequest request) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);
        GenerationModeDecision decision = generationModeRouter.route(request, codeGenType, workspace);
        GenerationPipelineRequest pipelineRequest = new GenerationPipelineRequest(request, codeGenType, workspace, decision);
        synchronized (generationSessionRegistry.lock(app.getId())) {
            resetResidualGenerationState(app.getId());
            generationSessionRegistry.assertNoActiveSession(app.getId());
            return dispatchRoutedPipeline(pipelineRequest);
        }
    }

    private GenerationTaskResult dispatchRoutedPipeline(GenerationPipelineRequest pipelineRequest) {
        for (GenerationPipeline pipeline : generationPipelines) {
            if (!pipeline.supports(pipelineRequest)) {
                continue;
            }
            var result = pipeline.execute(pipelineRequest);
            if (result.isPresent()) {
                return result.get();
            }
            return handleRoutedPipelineFallback(pipelineRequest, pipeline);
        }
        if (pipelineRequest.modeDecision().mode() == GenerationMode.HEAVY_EXPERT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可用的专家生成管线");
        }
        return handleRoutedPipelineFallback(pipelineRequest, null);
    }

    private GenerationTaskResult handleRoutedPipelineFallback(GenerationPipelineRequest pipelineRequest,
                                                             GenerationPipeline failedPipeline) {
        GenerationModeDecision decision = pipelineRequest.modeDecision();
        if (decision.mode() == GenerationMode.CREATE) {
            String route = failedPipeline == null ? decision.route() : failedPipeline.route();
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "CREATE 模板生成未完成，首次生成不会在生成前或生成中升级 Heavy: " + route);
        }
        if (decision.fallbackPolicy() != FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT) {
            String route = failedPipeline == null ? decision.route() : failedPipeline.route();
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成模式没有可用管线: " + route);
        }
        String failedRoute = failedPipeline == null ? decision.route() : failedPipeline.route();
        String reason = "pipeline_failed_or_unavailable:" + failedRoute;
        generationEventPublisher.publish(pipelineRequest.taskRequest(), GenerationEventType.TASK_ROUTE, "生成路径升级为专家模式", Map.of(
                "mode", GenerationMode.HEAVY_EXPERT.name(),
                "route", HeavyGenerationPipeline.ROUTE,
                "routerReason", decision.reason(),
                "fallbackReason", reason
        ));
        GenerationModeDecision fallbackDecision = decision.withFallback(GenerationMode.HEAVY_EXPERT, reason);
        return startHeavyGeneration(new GenerationPipelineRequest(
                pipelineRequest.taskRequest(),
                pipelineRequest.codeGenType(),
                pipelineRequest.workspace(),
                fallbackDecision
        ));
    }

    public GenerationTaskResult startHeavyGeneration(GenerationPipelineRequest pipelineRequest) {
        GenerationTaskRequest request = pipelineRequest.taskRequest();
        App app = request.app();
        CodeGenTypeEnum codeGenType = pipelineRequest.codeGenType();
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用重型生成路径", Map.of(
                "mode", pipelineRequest.modeDecision().mode().name(),
                "route", pipelineRequest.modeDecision().route(),
                "reason", pipelineRequest.modeDecision().reason(),
                "fallbackReason", pipelineRequest.modeDecision().fallbackReason(),
                "codeGenType", codeGenType.getValue()
        ));
        Instant prepareStartedAt = Instant.now();
        GenerationPreparation preparation;
        try {
            preparation = heavyGenerationPreparationService.prepare(app, request.message());
        } catch (Exception e) {
            throw e;
        }
        generationPerformanceMonitorService.startTask(
                preparation.taskId(),
                app.getId(),
                request.loginUser().getId(),
                pipelineRequest.modeDecision().route(),
                codeGenType.getValue(),
                prepareStartedAt,
                pipelineRequest.modeDecision()
        );
        generationPerformanceMonitorService.recordSpan(
                preparation.taskId(),
                "heavy_prepare",
                "success",
                Duration.between(prepareStartedAt, Instant.now()),
                ""
        );
        GenerationSession session = openGenerationSession(app.getId(), request.message(), request.loginUser(), preparation);
        startGenerationTask(app.getId(), request.loginUser(), preparation, session, request);
        return new GenerationTaskResult(preparation.taskId(), pipelineRequest.modeDecision().route(), pipelineRequest.workspace(), session.asFlux());
    }

    public Flux<GenerationStreamEvent> getStream(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        ThrowUtils.throwIf(session == null, ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        return session.asFlux();
    }

    public void stop(Long appId, User loginUser) {
        GenerationSession session = generationSessionRegistry.get(appId);
        ThrowUtils.throwIf(session == null || !session.isActive(), ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        session.cancel();
        generationAppStateService.markGenerationFinished(appId);
        session.emitStopped();
        completeGenerationSession(session, session.preparation(), "cancelled");
        if (session.preparation() != null) {
            generationPerformanceMonitorService.finishTask(session.preparation().taskId(), "cancelled");
        }
        generationSessionRegistry.remove(appId, session);
        generationToolExecutionContextService.clearContext(appId);
    }

    private GenerationSession openGenerationSession(Long appId,
                                                    String message,
                                                    User loginUser,
                                                    GenerationPreparation preparation) {
        GenerationSession session;
        synchronized (generationSessionRegistry.lock(appId)) {
            generationSessionRegistry.assertNoActiveSession(appId);
            resetResidualGenerationState(appId);
            generationTaskLifecycleService.recordUserMessage(appId, loginUser.getId(), message);
            generationTaskLifecycleService.startTrace(
                    preparation.taskId(),
                    appId,
                    loginUser.getId(),
                    preparation.originalType(),
                    preparation.targetType(),
                    message,
                    preparation.enhancedMessage(),
                    preparation.requiresBuildValidation(),
                    preparation.qualityGateLevel(),
                    orchestrationMode(preparation)
            );
            if (preparation.upgradeRequired()) {
                generationAppStateService.switchAppCodeGenType(appId, preparation.targetType());
            }
            generationAppStateService.markGenerationStarted(appId, preparation.generatingStage());
            updateGenerationPhase(appId, AppConstant.GENERATING_STAGE_AGENT, "智能体正在分析需求并规划生成策略...");
            session = new GenerationSession(preparation);
            session.bindTraceContext(generationTraceService, appId, loginUser.getId());
            generationSessionRegistry.put(appId, session);
        }
        return session;
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
                generationEventPublisher.publish(request, GenerationEventType.GENERATION_START, "重型生成任务开始", Map.of(
                        "taskId", preparation.taskId(),
                        "route", HeavyGenerationPipeline.ROUTE
                ));
                preparation.events().forEach(session::emit);
                markGenerationStage(appId, preparation.generatingStage(), "智能体编排完成，正在生成项目代码...");
                GenerationPerformanceMonitorService.SpanTimer generationSpan =
                        generationPerformanceMonitorService.startSpan(preparation.taskId(), "llm_generation");
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
            } catch (GenerationStoppedException e) {
                log.info("应用生成任务已停止，appId: {}", appId);
                finishCancelledGeneration(appId, session, preparation);
            } catch (Exception e) {
                log.error("应用生成任务执行失败，appId: {}", appId, e);
                generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "重型生成任务失败", Map.of(
                        "taskId", preparation.taskId(),
                        "error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
                ));
                heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, e);
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, "failed");
                generationPerformanceMonitorService.finishTask(preparation.taskId(), "failed");
                generationSessionRegistry.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
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
        markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "代码已生成，正在后台构建校验...");
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            String completionStatus = "success";
            GenerationPerformanceMonitorService.SpanTimer buildSpan =
                    generationPerformanceMonitorService.startSpan(preparation.taskId(), "build_validation");
            try {
                boolean buildSucceeded = heavyGenerationBuildValidationService.runWithAutoRepair(
                        appId, loginUser, preparation, session);
                if (buildSucceeded) {
                    buildSpan.success();
                    GenerationPerformanceMonitorService.SpanTimer finalizeSpan =
                            generationPerformanceMonitorService.startSpan(preparation.taskId(), "finalization");
                    try {
                        heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                        heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
                        finalizeSpan.success();
                    } catch (Exception e) {
                        finalizeSpan.failed(e.getMessage());
                        throw e;
                    }
                } else {
                    completionStatus = "failed";
                    buildSpan.failed("build validation failed");
                }
            } catch (Exception e) {
                completionStatus = "failed";
                buildSpan.failed(e.getMessage());
                log.error("后台构建校验失败，appId: {}", appId, e);
                heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, e);
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
                generationPerformanceMonitorService.finishTask(
                        preparation.taskId(), session.isCancelled() ? "cancelled" : completionStatus);
                generationSessionRegistry.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
                publishCompletion(request, preparation, completionStatus);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void startBackgroundFinalization(Long appId,
                                             User loginUser,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             GenerationTaskRequest request) {
        markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "代码已生成，正在后台整理生成结果...");
        Thread.startVirtualThread(() -> {
            MonitorContextHolder.setContext(
                    MonitorContext.builder()
                            .userId(loginUser.getId().toString())
                            .appId(appId.toString())
                            .taskId(preparation.taskId())
                            .build()
            );
            String completionStatus = "success";
            GenerationPerformanceMonitorService.SpanTimer finalizeSpan =
                    generationPerformanceMonitorService.startSpan(preparation.taskId(), "finalization");
            try {
                heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
                finalizeSpan.success();
            } catch (Exception e) {
                completionStatus = "failed";
                finalizeSpan.failed(e.getMessage());
                log.error("后台整理生成结果失败，appId: {}", appId, e);
                heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, e);
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
                generationPerformanceMonitorService.finishTask(
                        preparation.taskId(), session.isCancelled() ? "cancelled" : completionStatus);
                generationSessionRegistry.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
                publishCompletion(request, preparation, completionStatus);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void finishCancelledGeneration(Long appId,
                                           GenerationSession session,
                                           GenerationPreparation preparation) {
        generationAppStateService.markGenerationFinished(appId);
        session.emitStopped();
        completeGenerationSession(session, preparation, "cancelled");
        generationPerformanceMonitorService.finishTask(preparation.taskId(), "cancelled");
        generationSessionRegistry.remove(appId, session);
        generationToolExecutionContextService.clearContext(appId);
    }

    private void publishCompletion(GenerationTaskRequest request, GenerationPreparation preparation, String status) {
        GenerationEventType eventType = "success".equals(status) ? GenerationEventType.TASK_DONE : GenerationEventType.TASK_FAILED;
        generationEventPublisher.publish(request, eventType, "重型生成任务结束", Map.of(
                "taskId", preparation.taskId(),
                "status", status,
                "route", HeavyGenerationPipeline.ROUTE
        ));
    }

    private void resetResidualGenerationState(Long appId) {
        GenerationSession session = generationSessionRegistry.get(appId);
        if (session == null) {
            return;
        }
        if (!session.isActive()) {
            generationSessionRegistry.remove(appId, session);
        }
    }

    private void updateGenerationPhase(Long appId, String generatingStage, String generatingMessage) {
        markGenerationStage(appId, generatingStage, generatingMessage);
    }

    private void markGenerationStage(Long appId, String generatingStage, String generatingMessage) {
        generationAppStateService.markGenerationStage(appId, generatingStage, generatingMessage, generationSessionRegistry.get(appId));
    }

    private void completeGenerationSession(GenerationSession session,
                                           GenerationPreparation preparation,
                                           String status) {
        heavyGenerationSessionCompletionService.complete(session, preparation, status);
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        return heavyGenerationSessionCompletionService.orchestrationMode(preparation);
    }
}
