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
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

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
    private final HeavyGenerationBuildValidationService heavyGenerationBuildValidationService;
    private final HeavyGenerationExecutionService heavyGenerationExecutionService;
    private final HeavyGenerationFailureRecoveryService heavyGenerationFailureRecoveryService;
    private final HeavyGenerationFinalizationService heavyGenerationFinalizationService;
    private final HeavyGenerationPreparationService heavyGenerationPreparationService;
    private final HeavyGenerationSessionCompletionService heavyGenerationSessionCompletionService;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GenerationTraceService generationTraceService;
    private final GenerationWorkspaceService generationWorkspaceService;

    public GenerationTaskResult start(GenerationTaskRequest request) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);
        GenerationPipelineRequest pipelineRequest = new GenerationPipelineRequest(request, codeGenType, workspace);
        synchronized (generationSessionRegistry.lock(app.getId())) {
            resetResidualGenerationState(app.getId());
            generationSessionRegistry.assertNoActiveSession(app.getId());
            for (GenerationPipeline pipeline : generationPipelines) {
                if (!pipeline.supports(pipelineRequest)) {
                    continue;
                }
                var result = pipeline.execute(pipelineRequest);
                if (result.isPresent()) {
                    return result.get();
                }
            }
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "没有可用的生成管线");
        }
    }

    public GenerationTaskResult startHeavyGeneration(GenerationPipelineRequest pipelineRequest) {
        GenerationTaskRequest request = pipelineRequest.taskRequest();
        App app = request.app();
        CodeGenTypeEnum codeGenType = pipelineRequest.codeGenType();
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用重型生成路径", Map.of(
                "route", HeavyGenerationPipeline.ROUTE,
                "reason", "pipeline_fallback_heavy_generation",
                "codeGenType", codeGenType.getValue()
        ));
        GenerationPreparation preparation = heavyGenerationPreparationService.prepare(app, request.message());
        GenerationSession session = openGenerationSession(app.getId(), request.message(), request.loginUser(), preparation);
        startGenerationTask(app.getId(), request.loginUser(), preparation, session, request);
        return new GenerationTaskResult(preparation.taskId(), HeavyGenerationPipeline.ROUTE, pipelineRequest.workspace(), session.asFlux());
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
                heavyGenerationExecutionService.runGenerationWithAutoRepair(appId, loginUser, preparation, session);
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
            try {
                boolean buildSucceeded = heavyGenerationBuildValidationService.runWithAutoRepair(
                        appId, loginUser, preparation, session);
                if (buildSucceeded) {
                    heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                    heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
                } else {
                    completionStatus = "failed";
                }
            } catch (Exception e) {
                completionStatus = "failed";
                log.error("后台构建校验失败，appId: {}", appId, e);
                heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, e);
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
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
            try {
                heavyGenerationFinalizationService.emitDiffSummaryIfAvailable(appId, preparation, session);
                heavyGenerationFinalizationService.emitCommitResultIfAvailable(appId, preparation, session);
            } catch (Exception e) {
                completionStatus = "failed";
                log.error("后台整理生成结果失败，appId: {}", appId, e);
                heavyGenerationFailureRecoveryService.emitGenerationError(appId, preparation, session, e);
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
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
