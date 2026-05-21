package com.rush.rushaicodemother.orchestration;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingService;
import com.rush.rushaicodemother.ai.AiCodeGenTypeRoutingServiceFactory;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.handler.StreamHandlerExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.MonitorContext;
import com.rush.rushaicodemother.monitor.MonitorContextHolder;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditService;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.review.OrphanFileReviewService;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchApplyService;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchResultService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationCommitService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.orchestration.template.ParallelSlotFillService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.template.TemplateSlotFillService;
import com.rush.rushaicodemother.orchestration.template.VueProjectTemplateBootstrapService;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.AppDatabaseResourceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.GenerationTraceService;
import com.rush.rushaicodemother.service.UserCreditService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import com.rush.rushaicodemother.service.impl.GenerationRepairPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class GenerationTaskOrchestrator {

    private static final String HEAVY_GENERATION_ROUTE = "heavy_generation";
    private static final String SLOT_FILL_ROUTE = "slot_fill";
    private static final int MAX_GENERATION_SNAPSHOT_CHARS = 20000;
    private static final long GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS = 1000;
    private static final int MAX_AUTO_REPAIR_ROUNDS = 1;
    private static final int MAX_PROJECT_INDEX_FILES = 80;
    private static final int MAX_MODEL_CONTEXT_FILE_CHARS = 12000;

    private final Map<Long, Object> generationLocks = new ConcurrentHashMap<>();
    private final Map<Long, GenerationSession> activeGenerationSessions = new ConcurrentHashMap<>();

    private final AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;
    private final AiCodeGeneratorFacade aiCodeGeneratorFacade;
    private final AppDatabaseResourceService appDatabaseResourceService;
    private final ChatHistoryService chatHistoryService;
    private final GenerationPerformanceSelector generationPerformanceSelector;
    private final LightweightEditService lightweightEditService;
    private final GenerationAppStateService generationAppStateService;
    private final GenerationCommitService generationCommitService;
    private final GenerationDiffSummaryService generationDiffSummaryService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationMemoryContextService generationMemoryContextService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationOrchestrator generationOrchestrator;
    private final GenerationPatchApplyService generationPatchApplyService;
    private final GenerationPatchResultService generationPatchResultService;
    private final GenerationRollbackRestoreService generationRollbackRestoreService;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;
    private final GenerationTraceService generationTraceService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final OrphanFileReviewService orphanFileReviewService;
    private final ParallelSlotFillService parallelSlotFillService;
    private final StreamHandlerExecutor streamHandlerExecutor;
    private final TemplateSlotFillService templateSlotFillService;
    private final UserCreditService userCreditService;
    private final VueProjectBuilder vueProjectBuilder;
    private final VueProjectTemplateBootstrapService vueProjectTemplateBootstrapService;

    public GenerationTaskResult start(GenerationTaskRequest request) {
        ThrowUtils.throwIf(request == null || request.app() == null || request.loginUser() == null,
                ErrorCode.PARAMS_ERROR, "生成任务参数错误");
        ThrowUtils.throwIf(StrUtil.isBlank(request.message()), ErrorCode.PARAMS_ERROR, "提示词不能为空");
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        GenerationWorkspace workspace = generationWorkspaceService.resolve(app, codeGenType);

        // Phase 2: 尝试轻量编辑路径
        try {
            LightweightEditResult editResult = lightweightEditService.execute(request);
            if (editResult != null) {
                log.info("轻量编辑路径完成，appId: {}, taskId: {}, route: {}", app.getId(), editResult.taskId(), editResult.route());
                // 如果轻量编辑失败，重置生成状态并抛出异常让前端显示错误信息
                if ("failed".equals(editResult.validationResult())) {
                    generationAppStateService.markGenerationFinished(app.getId());
                    throw new BusinessException(ErrorCode.OPERATION_ERROR, editResult.summary());
                }
                // 轻量编辑成功，创建 session 并存入 activeGenerationSessions
                GenerationSession editSession = new GenerationSession(null);
                activeGenerationSessions.put(app.getId(), editSession);
                editSession.emit(GenerationStreamEvent.agentEvent(
                        editResult.summary(),
                        Map.of("route", editResult.route(), "taskId", editResult.taskId(), "status", editResult.validationResult())
                ));
                editSession.complete();
                // 延迟清理 session，确保前端有时间获取事件
                scheduleSessionCleanup(app.getId(), editSession);
                return new GenerationTaskResult(editResult.taskId(), editResult.route(), workspace, editSession.asFlux());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("轻量编辑路径异常，回退到重型生成，appId: {}, error: {}", app.getId(), e.getMessage());
        }

        // Phase 4: 尝试模板 slot 填充路径（首次生成）
        if (codeGenType == CodeGenTypeEnum.VUE_PROJECT && !hasGeneratedCode(app)) {
            try {
                SlotFillResult slotFillResult = trySlotFillGeneration(app, request);
                if (slotFillResult != null) {
                    log.info("模板 slot 填充路径完成，appId: {}, templateId: {}, filledSlots: {}",
                            app.getId(), slotFillResult.templateId(), slotFillResult.filledSlotCount());
                    GenerationSession slotFillSession = new GenerationSession(null);
                    slotFillSession.emit(GenerationStreamEvent.agentEvent(
                            slotFillResult.summary(),
                            Map.of(
                                    "route", SLOT_FILL_ROUTE,
                                    "templateId", slotFillResult.templateId(),
                                    "filledSlots", slotFillResult.filledSlots(),
                                    "totalChars", slotFillResult.totalChars()
                            )
                    ));
                    slotFillSession.complete();
                    String taskId = "slot_fill_" + System.currentTimeMillis();
                    // 扣减用户积分（slot 填充成功后）
                    userCreditService.chargeGenerationTask(taskId);
                    return new GenerationTaskResult(taskId, SLOT_FILL_ROUTE, workspace, slotFillSession.asFlux());
                }
            } catch (Exception e) {
                log.warn("模板 slot 填充路径异常，回退到重型生成，appId: {}, error: {}", app.getId(), e.getMessage());
            }
        }

        // 重型生成路径
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用重型生成路径", Map.of(
                "route", HEAVY_GENERATION_ROUTE,
                "reason", "phase_1_default_heavy_path",
                "codeGenType", codeGenType.getValue()
        ));
        GenerationPreparation preparation = prepareGeneration(app, request.message());
        GenerationSession session = openGenerationSession(app.getId(), request.message(), request.loginUser(), preparation);
        startGenerationTask(app.getId(), request.loginUser(), preparation, session, request);
        return new GenerationTaskResult(preparation.taskId(), HEAVY_GENERATION_ROUTE, workspace, session.asFlux());
    }

    public Flux<GenerationStreamEvent> getStream(Long appId) {
        GenerationSession session = activeGenerationSessions.get(appId);
        ThrowUtils.throwIf(session == null, ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        return session.asFlux();
    }

    public void stop(Long appId, User loginUser) {
        GenerationSession session = activeGenerationSessions.get(appId);
        ThrowUtils.throwIf(session == null || !session.isActive(), ErrorCode.OPERATION_ERROR, "当前应用没有进行中的生成任务");
        session.cancel();
        generationAppStateService.markGenerationFinished(appId);
        session.emitStopped();
        completeGenerationSession(session, session.preparation(), "cancelled");
        activeGenerationSessions.remove(appId, session);
        generationToolExecutionContextService.clearContext(appId);
    }

    private GenerationSession openGenerationSession(Long appId,
                                                    String message,
                                                    User loginUser,
                                                    GenerationPreparation preparation) {
        GenerationSession session;
        synchronized (getGenerationLock(appId)) {
            ThrowUtils.throwIf(activeGenerationSessions.containsKey(appId), ErrorCode.OPERATION_ERROR, "当前应用正在生成中，请稍后再试");
            resetResidualGenerationState(appId);
            chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
            generationTraceService.startTask(
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
            activeGenerationSessions.put(appId, session);
        }
        return session;
    }

    private void startGenerationTask(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session,
                                     GenerationTaskRequest request) {
        Thread.startVirtualThread(() -> {
            StringBuilder generatedContent = new StringBuilder();
            long[] lastSnapshotUpdateAt = {0L};
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
                        "route", HEAVY_GENERATION_ROUTE
                ));
                preparation.events().forEach(session::emit);
                markGenerationStage(appId, preparation.generatingStage(), "智能体编排完成，正在生成项目代码...");
                runGenerationWithAutoRepair(appId, loginUser, preparation, session, generatedContent, lastSnapshotUpdateAt);
                if (session.isCancelled()) {
                    generationAppStateService.markGenerationFinished(appId);
                    session.emitStopped();
                    completeGenerationSession(session, preparation, "cancelled");
                    activeGenerationSessions.remove(appId, session);
                    generationToolExecutionContextService.clearContext(appId);
                    return;
                }
                if (preparation.requiresBuildValidation()) {
                    startBackgroundBuild(appId, loginUser, preparation, session, request);
                } else {
                    startBackgroundFinalization(appId, loginUser, preparation, session, request);
                }
            } catch (GenerationStoppedException e) {
                log.info("应用生成任务已停止，appId: {}", appId);
                generationAppStateService.markGenerationFinished(appId);
                session.emitStopped();
                completeGenerationSession(session, preparation, "cancelled");
                activeGenerationSessions.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
            } catch (Exception e) {
                log.error("应用生成任务执行失败，appId: {}", appId, e);
                generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "重型生成任务失败", Map.of(
                        "taskId", preparation.taskId(),
                        "error", StrUtil.blankToDefault(e.getMessage(), e.getClass().getSimpleName())
                ));
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                generationAppStateService.markGenerationFinished(appId);
                session.emit(GenerationStreamEvent.generationError(
                        generationError.message(),
                        buildGenerationErrorData(preparation, generationError)
                ));
                completeGenerationSession(session, preparation, "failed");
                activeGenerationSessions.remove(appId, session);
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
                boolean buildSucceeded = runBackgroundBuildWithAutoRepair(appId, loginUser, preparation, session);
                if (buildSucceeded) {
                    emitDiffSummaryIfAvailable(appId, preparation, session);
                    emitCommitResultIfAvailable(appId, preparation, session);
                } else {
                    completionStatus = "failed";
                }
            } catch (Exception e) {
                completionStatus = "failed";
                log.error("后台构建校验失败，appId: {}", appId, e);
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                session.emit(GenerationStreamEvent.generationError(
                        generationError.message(),
                        buildGenerationErrorData(preparation, generationError)
                ));
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
                activeGenerationSessions.remove(appId, session);
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
                emitDiffSummaryIfAvailable(appId, preparation, session);
                emitCommitResultIfAvailable(appId, preparation, session);
            } catch (Exception e) {
                completionStatus = "failed";
                log.error("后台整理生成结果失败，appId: {}", appId, e);
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                session.emit(GenerationStreamEvent.generationError(
                        generationError.message(),
                        buildGenerationErrorData(preparation, generationError)
                ));
            } finally {
                generationAppStateService.markGenerationFinished(appId);
                completeGenerationSession(session, preparation, session.isCancelled() ? "cancelled" : completionStatus);
                activeGenerationSessions.remove(appId, session);
                generationToolExecutionContextService.clearContext(appId);
                publishCompletion(request, preparation, completionStatus);
                MonitorContextHolder.clearContext();
            }
        });
    }

    private void publishCompletion(GenerationTaskRequest request, GenerationPreparation preparation, String status) {
        GenerationEventType eventType = "success".equals(status) ? GenerationEventType.TASK_DONE : GenerationEventType.TASK_FAILED;
        generationEventPublisher.publish(request, eventType, "重型生成任务结束", Map.of(
                "taskId", preparation.taskId(),
                "status", status,
                "route", HEAVY_GENERATION_ROUTE
        ));
    }

    private boolean runBackgroundBuildWithAutoRepair(Long appId,
                                                     User loginUser,
                                                     GenerationPreparation preparation,
                                                     GenerationSession session) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                + preparation.targetType().getValue() + "_" + appId
                + (preparation.targetType() == CodeGenTypeEnum.FULL_STACK_PROJECT ? File.separator + "frontend" : "");
        StringBuilder generatedContent = new StringBuilder();
        long[] lastSnapshotUpdateAt = {0L};
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            emitRollbackRestoreIfAllowed(appId, preparation, session);
            emitMissingProjectCodeError(appId, preparation, session, workspaceState);
            rollbackCodeGenTypeIfNeeded(appId, preparation);
            return false;
        }
        VueProjectBuilder.BuildResult buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
        if (session.isCancelled()) {
            return false;
        }
        session.emit(GenerationStreamEvent.buildResult(buildResult.toDiagnosticReport(), Map.of(
                "success", buildResult.success(),
                "stage", buildResult.stage(),
                "projectPath", buildResult.projectPath(),
                "summary", buildResult.summary(),
                "report", buildResult.toDiagnosticReport(),
                "taskId", preparation.taskId(),
                "qualityGate", preparation.qualityGateLevel(),
                "willAutoRepair", !buildResult.success() && workspaceState.canAutoRepair() && MAX_AUTO_REPAIR_ROUNDS > 0
        )));
        if (buildResult.success()) {
            return true;
        }
        if (MAX_AUTO_REPAIR_ROUNDS <= 0 || !workspaceState.canAutoRepair()) {
            emitRollbackRestoreIfAllowed(appId, preparation, session);
            rollbackCodeGenTypeIfNeeded(appId, preparation);
            GenerationErrorClassifier.GenerationError generationError =
                    classifyGenerationError(buildResult.toFailureSummary());
            session.emit(GenerationStreamEvent.generationError(
                    buildResult.toFailureSummary(),
                    buildGenerationErrorData(preparation, generationError, buildResult.toFailureSummary())
            ));
            return false;
        }
        for (int round = 1; round <= MAX_AUTO_REPAIR_ROUNDS; round++) {
            session.throwIfCancelled();
            workspaceState = GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
            if (!workspaceState.canAutoRepair()) {
                emitRollbackRestoreIfAllowed(appId, preparation, session);
                emitMissingProjectCodeError(appId, preparation, session, workspaceState);
                rollbackCodeGenTypeIfNeeded(appId, preparation);
                return false;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_REPAIR, "构建未通过，正在自动修复...");
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "started");
            session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                    "round", round,
                    "maxRounds", MAX_AUTO_REPAIR_ROUNDS,
                    "taskId", preparation.taskId(),
                    "agent", "BuildFix"
            )));
            try {
                executeGenerationRound(
                        appId,
                        loginUser,
                        preparation.targetType(),
                        buildAutoRepairPrompt(
                                appId,
                                preparation,
                                new BusinessException(ErrorCode.SYSTEM_ERROR, buildResult.toFailureSummary()),
                                round
                        ),
                        session,
                        generatedContent,
                        lastSnapshotUpdateAt
                );
            } catch (Exception e) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
                throw e;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "自动修复完成，正在重新构建校验...");
            buildResult = vueProjectBuilder.buildProjectWithResult(projectPath);
            if (session.isCancelled()) {
                return false;
            }
            session.emit(GenerationStreamEvent.buildResult(buildResult.toDiagnosticReport(), Map.of(
                    "success", buildResult.success(),
                    "stage", buildResult.stage(),
                    "projectPath", buildResult.projectPath(),
                    "summary", buildResult.summary(),
                    "report", buildResult.toDiagnosticReport(),
                    "taskId", preparation.taskId(),
                    "qualityGate", preparation.qualityGateLevel()
            )));
            if (buildResult.success()) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "success");
                return true;
            }
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
        }
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        GenerationErrorClassifier.GenerationError generationError =
                classifyGenerationError(buildResult.toFailureSummary());
        session.emit(GenerationStreamEvent.generationError(
                buildResult.toFailureSummary(),
                buildGenerationErrorData(preparation, generationError, buildResult.toFailureSummary())
        ));
        return false;
    }

    private void runGenerationWithAutoRepair(Long appId,
                                              User loginUser,
                                              GenerationPreparation preparation,
                                              GenerationSession session,
                                              StringBuilder generatedContent,
                                              long[] lastSnapshotUpdateAt) {
        String currentPrompt = preparation.enhancedMessage();
        Exception lastError = null;
        int maxGenerationRepairRounds = GenerationRepairPolicy.allowAutoRepair(
                preparation.generatingStage(),
                preparation.targetType(),
                MAX_AUTO_REPAIR_ROUNDS
        ) && preparation.requiresBuildValidation() ? MAX_AUTO_REPAIR_ROUNDS : 0;

        // 选择生成性能配置
        boolean isFirstGeneration = AppConstant.GENERATING_STAGE_CREATE.equals(preparation.generatingStage());
        boolean isComplex = isComplexPrompt(currentPrompt);
        GenerationPerformanceProfile profile = generationPerformanceSelector.select(
                isFirstGeneration, isComplex, preparation.targetType());

        for (int round = 0; round <= maxGenerationRepairRounds; round++) {
            session.throwIfCancelled();
            if (round > 0) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "started");
                session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                        "round", round,
                        "maxRounds", maxGenerationRepairRounds,
                        "taskId", preparation.taskId(),
                        "agent", "BuildFix"
                )));
                // 修复轮次使用质量优先配置
                profile = GenerationPerformanceProfile.qualityFirst();
            }
            try {
                executeGenerationRound(appId, loginUser, preparation.targetType(), currentPrompt,
                        session, generatedContent, lastSnapshotUpdateAt, profile);
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "success");
                }
                return;
            } catch (Exception e) {
                lastError = e;
                GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(e);
                log.warn("应用生成轮次失败，appId: {}, round: {}, category: {}, error: {}",
                        appId, round, generationError.category(), e.getMessage());
                if (round > 0) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "generation", "failed");
                }
                if (e instanceof MissingGeneratedProjectException || !generationError.recoverable()) {
                    break;
                }
                if (round >= maxGenerationRepairRounds) {
                    break;
                }
                currentPrompt = buildAutoRepairPrompt(appId, preparation, e, round + 1);
            }
        }
        throw new BusinessException(ErrorCode.SYSTEM_ERROR,
                lastError == null ? "生成失败" : StrUtil.blankToDefault(lastError.getMessage(), "生成失败"));
    }

    /**
     * 判断提示词是否为复杂请求。
     */
    private boolean isComplexPrompt(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return false;
        }
        String normalized = prompt.toLowerCase();
        return normalized.contains("vue") || normalized.contains("组件") || normalized.contains("路由")
                || normalized.contains("模块") || normalized.contains("后台") || normalized.contains("管理系统")
                || normalized.contains("登录") || normalized.contains("注册") || normalized.contains("api")
                || normalized.contains("接口") || normalized.contains("状态管理") || normalized.contains("多页面")
                || normalized.contains("工作台") || normalized.contains("dashboard") || normalized.contains("crud");
    }

    private void executeGenerationRound(Long appId,
                                         User loginUser,
                                         CodeGenTypeEnum codeGenType,
                                         String prompt,
                                         GenerationSession session,
                                         StringBuilder generatedContent,
                                         long[] lastSnapshotUpdateAt) {
        executeGenerationRound(appId, loginUser, codeGenType, prompt, session, generatedContent, lastSnapshotUpdateAt, null);
    }

    /**
     * 执行一轮代码生成。
     *
     * @param appId              应用 ID
     * @param loginUser          登录用户
     * @param codeGenType        代码生成类型
     * @param prompt             生成提示词
     * @param session            生成会话
     * @param generatedContent   已生成内容
     * @param lastSnapshotUpdateAt 上次快照更新时间
     * @param profile            性能配置，null 表示使用默认配置
     */
    private void executeGenerationRound(Long appId,
                                         User loginUser,
                                         CodeGenTypeEnum codeGenType,
                                         String prompt,
                                         GenerationSession session,
                                         StringBuilder generatedContent,
                                         long[] lastSnapshotUpdateAt,
                                         GenerationPerformanceProfile profile) {
        Flux<GenerationStreamEvent> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(
                prompt, codeGenType, appId, session::isCancelled, session::setResponseHandle, profile);
        streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenType)
                .takeUntilOther(session.cancelSignal())
                .doOnNext(event -> {
                    session.throwIfCancelled();
                    appendGenerationSnapshotChunk(generatedContent, event.getText());
                    updateGenerationSnapshotIfDue(appId, generatedContent, lastSnapshotUpdateAt);
                    session.emit(event);
                })
                .doOnComplete(session::throwIfCancelled)
                .blockLast();
        verifyGeneratedProjectReady(appId, codeGenType);
    }

    private void emitDiffSummaryIfAvailable(Long appId,
                                            GenerationPreparation preparation,
                                            GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationArtifact rollbackPoint = preparation.artifact("rollback_point");
        DiffSummary summary = generationDiffSummaryService.summarize(
                appId,
                preparation.targetType(),
                preparation.taskId(),
                rollbackPoint
        );
        GenerationArtifact diffSummary = GenerationArtifact.of(
                "diff_summary",
                "Orchestrator",
                "生成后差异摘要",
                summary.toPayload()
        );
        preparation.putArtifact(diffSummary);
        session.emit(GenerationStreamEvent.agentEvent(
                generationDiffSummaryService.renderText(summary),
                buildDiffSummaryEventData(preparation, diffSummary)
        ));
        emitPatchResultIfAvailable(appId, preparation, session, diffSummary);
    }

    private void emitPatchResultIfAvailable(Long appId,
                                            GenerationPreparation preparation,
                                            GenerationSession session,
                                            GenerationArtifact diffSummary) {
        if (session.isCancelled()) {
            return;
        }
        PatchResult patchResult = generationPatchResultService.evaluate(
                appId,
                preparation.taskId(),
                preparation.artifact("change_plan"),
                diffSummary
        );
        GenerationArtifact patchResultArtifact = GenerationArtifact.of(
                "patch_result",
                "Orchestrator",
                "Patch 实际落盘结果",
                patchResult.toPayload()
        );
        preparation.putArtifact(patchResultArtifact);
        generationOrchestrationMetricsCollector.recordPatchResult(
                "agent",
                patchResult.status(),
                patchResult.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                generationPatchResultService.renderText(patchResult),
                buildPatchResultEventData(preparation, patchResultArtifact)
        ));
        emitOrphanFileReviewIfAvailable(appId, preparation, session);
    }

    private void emitOrphanFileReviewIfAvailable(Long appId,
                                                 GenerationPreparation preparation,
                                                 GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        Path projectRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, preparation.targetType().getValue() + "_" + appId);
        ChangePlan changePlan = preparation.artifact("change_plan") == null
                ? null
                : ChangePlan.fromPayload(preparation.artifact("change_plan").payload());
        OrphanFileReviewService.OrphanFileReviewResult result = orphanFileReviewService.review(projectRoot, changePlan);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("orphanCandidates", result.orphanCandidates());
        payload.put("reasons", result.reasons());
        payload.put("deleteAllowedFiles", result.deleteAllowedFiles());
        payload.put("summary", result.summary());
        GenerationArtifact artifact = GenerationArtifact.of("orphan_file_review", "Orchestrator", "旧模板残留审查", payload);
        preparation.putArtifact(artifact);
        session.emit(GenerationStreamEvent.agentEvent(
                result.summary(),
                buildOrphanReviewEventData(preparation, artifact)
        ));
    }

    private void emitCommitResultIfAvailable(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationCommitResult commitResult = generationCommitService.commit(
                appId,
                preparation.taskId(),
                preparation.artifact("diff_summary")
        );
        GenerationArtifact commitArtifact = GenerationArtifact.of(
                "generation_commit",
                "Orchestrator",
                "生成结果本地 Git 提交",
                commitResult.toPayload()
        );
        preparation.putArtifact(commitArtifact);
        generationOrchestrationMetricsCollector.recordGenerationCommit(
                commitResult.provider(),
                commitResult.status(),
                commitResult.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                generationCommitService.renderText(commitResult),
                buildCommitResultEventData(preparation, commitArtifact)
        ));
    }

    private Map<String, Object> buildDiffSummaryEventData(GenerationPreparation preparation,
                                                          GenerationArtifact diffSummary) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "diff");
        data.put("status", diffSummary.payload().get("status"));
        data.put("summary", "created".equals(String.valueOf(diffSummary.payload().get("status")))
                ? "生成后差异摘要已生成"
                : "生成后差异摘要已跳过");
        data.put("taskId", preparation.taskId());
        data.put("artifact", diffSummary.payload());
        return data;
    }

    private Map<String, Object> buildPatchResultEventData(GenerationPreparation preparation,
                                                          GenerationArtifact patchResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "patch");
        data.put("status", patchResult.payload().get("status"));
        data.put("summary", "applied".equals(String.valueOf(patchResult.payload().get("status")))
                ? "Patch 实际落盘结果已对齐"
                : "Patch 实际落盘结果存在偏差或已跳过");
        data.put("taskId", preparation.taskId());
        data.put("artifact", patchResult.payload());
        return data;
    }

    private Map<String, Object> buildOrphanReviewEventData(GenerationPreparation preparation,
                                                           GenerationArtifact orphanReview) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "orphan_review");
        data.put("status", orphanReview.payload().get("status"));
        data.put("summary", orphanReview.payload().get("summary"));
        data.put("taskId", preparation.taskId());
        data.put("artifact", orphanReview.payload());
        return data;
    }

    private Map<String, Object> buildCommitResultEventData(GenerationPreparation preparation,
                                                           GenerationArtifact commitResult) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "commit");
        data.put("status", commitResult.payload().get("status"));
        data.put("summary", "committed".equals(String.valueOf(commitResult.payload().get("status")))
                ? "生成结果已提交到本地 Git"
                : "生成结果本地 Git 提交已跳过或失败");
        data.put("taskId", preparation.taskId());
        data.put("artifact", commitResult.payload());
        return data;
    }

    private void emitRollbackRestoreIfAllowed(Long appId,
                                              GenerationPreparation preparation,
                                              GenerationSession session) {
        if (session.isCancelled() || preparation.artifact("rollback_restore") != null) {
            return;
        }
        GenerationArtifact rollbackRestore = generationRollbackRestoreService.restoreIfAllowed(
                appId,
                preparation.taskId(),
                preparation.artifact("change_plan"),
                preparation.artifact("rollback_point")
        );
        preparation.putArtifact(rollbackRestore);
        Object status = rollbackRestore.payload().get("status");
        Object reason = rollbackRestore.payload().get("reason");
        generationOrchestrationMetricsCollector.recordRollbackRestore("agent", String.valueOf(status), String.valueOf(reason));
        session.emit(GenerationStreamEvent.agentEvent(
                buildRollbackRestoreMessage(rollbackRestore),
                buildRollbackRestoreEventData(preparation, rollbackRestore)
        ));
    }

    private String buildRollbackRestoreMessage(GenerationArtifact rollbackRestore) {
        Object status = rollbackRestore.payload().get("status");
        if ("restored".equals(String.valueOf(status))) {
            return "生成失败，已从本地回滚点恢复项目文件。";
        }
        if ("failed".equals(String.valueOf(status))) {
            return "生成失败，尝试从本地回滚点恢复项目文件未成功。";
        }
        return "生成失败，当前回滚策略未执行自动恢复。";
    }

    private Map<String, Object> buildRollbackRestoreEventData(GenerationPreparation preparation,
                                                              GenerationArtifact rollbackRestore) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "rollback");
        data.put("status", rollbackRestore.payload().get("status"));
        data.put("summary", buildRollbackRestoreMessage(rollbackRestore));
        data.put("taskId", preparation.taskId());
        data.put("artifact", rollbackRestore.payload());
        return data;
    }

    private void verifyGeneratedProjectReady(Long appId, CodeGenTypeEnum codeGenType) {
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenType.getValue() + "_" + appId;
        if (codeGenType == CodeGenTypeEnum.BACKEND_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "go.mod").isFile()
                    && new File(projectDir, "cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效后端工程，请重试生成");
            return;
        }
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            File projectDir = new File(projectPath);
            boolean ready = projectDir.isDirectory()
                    && new File(projectDir, "frontend/package.json").isFile()
                    && new File(projectDir, "backend/go.mod").isFile()
                    && new File(projectDir, "backend/cmd/server/main.go").isFile();
            ThrowUtils.throwIf(!ready, ErrorCode.SYSTEM_ERROR, "生成结束但未发现有效全栈工程，请重试生成");
            return;
        }
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            return;
        }
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            throw new MissingGeneratedProjectException(workspaceState);
        }
    }

    private void emitMissingProjectCodeError(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        String message = buildMissingProjectCodeMessage(workspaceState);
        log.warn("生成结束但未发现有效项目代码，appId: {}, projectPath: {}, fileCount: {}, meaningfulFileCount: {}, keyFiles: {}",
                appId,
                workspaceState.rootPath(),
                workspaceState.fileCount(),
                workspaceState.meaningfulFileCount(),
                workspaceState.detectedKeyFiles());
        session.emit(GenerationStreamEvent.generationError(message, buildGenerationErrorData(
                preparation,
                "codegen_empty",
                message,
                true,
                Map.of(
                        "projectPath", workspaceState.rootPath().toString(),
                        "fileCount", workspaceState.fileCount(),
                        "meaningfulFileCount", workspaceState.meaningfulFileCount()
                )
        )));
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         GenerationErrorClassifier.GenerationError generationError) {
        return buildGenerationErrorData(preparation, generationError, generationError.message());
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         GenerationErrorClassifier.GenerationError generationError,
                                                         String message) {
        return buildGenerationErrorData(
                preparation,
                generationError.category(),
                message,
                generationError.recoverable(),
                Map.of()
        );
    }

    private Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                         String category,
                                                         String message,
                                                         boolean recoverable,
                                                         Map<String, Object> extraData) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("category", category);
        data.put("message", message);
        data.put("taskId", preparation.taskId());
        data.put("recoverable", recoverable);
        if (extraData != null) {
            data.putAll(extraData);
        }
        GenerationArtifact rollbackPoint = preparation.artifacts() == null ? null : preparation.artifacts().get("rollback_point");
        if (rollbackPoint != null) {
            data.put("rollback_point", rollbackPoint.payload());
        }
        GenerationArtifact diffSummary = preparation.artifacts() == null ? null : preparation.artifacts().get("diff_summary");
        if (diffSummary != null) {
            data.put("diff_summary", diffSummary.payload());
        }
        GenerationArtifact patchResult = preparation.artifacts() == null ? null : preparation.artifacts().get("patch_result");
        if (patchResult != null) {
            data.put("patch_result", patchResult.payload());
        }
        GenerationArtifact commitResult = preparation.artifacts() == null ? null : preparation.artifacts().get("generation_commit");
        if (commitResult != null) {
            data.put("generation_commit", commitResult.payload());
        }
        GenerationArtifact rollbackRestore = preparation.artifacts() == null ? null : preparation.artifacts().get("rollback_restore");
        if (rollbackRestore != null) {
            data.put("rollback_restore", rollbackRestore.payload());
        }
        return data;
    }

    private String buildMissingProjectCodeMessage(GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        return workspaceState.missingProjectSummary()
                + "。请重试生成；如果持续出现，请检查模型工具调用是否成功写入关键项目文件。";
    }

    private String buildAutoRepairPrompt(Long appId,
                                         GenerationPreparation preparation,
                                         Exception exception,
                                         int repairRound) {
        String errorMessage = StrUtil.blankToDefault(exception.getMessage(), "构建失败");
        String memoryContext = generationMemoryContextService.buildAutoRepairMemoryContext(
                appId,
                preparation == null ? null : preparation.taskId(),
                errorMessage,
                repairRound
        );
        String memorySection = StrUtil.isBlank(memoryContext) ? "" : "\n" + memoryContext + "\n";
        return """
                【自动修复任务】
                上一次 Vue 项目生成后未通过本地构建。请基于当前项目文件直接修复，不要重建整个项目。
                %s
                修复轮次：%d
                错误分类：%s
                错误摘要：
                %s

                必须遵守：
                1. 先使用项目搜索、目录读取或批量读取文件工具定位问题。
                2. 如果涉及依赖、scripts 或 package.json，先使用依赖问题分析工具，再用依赖与脚本管理工具处理。
                3. 只修改必要文件，避免无关重构。
                4. 修复后必须调用本地构建诊断工具验证。
                """.formatted(memorySection, repairRound, classifyGenerationError(errorMessage).category(), errorMessage);
    }

    private GenerationErrorClassifier.GenerationError classifyGenerationError(Throwable throwable) {
        return GenerationErrorClassifier.classify(throwable);
    }

    private GenerationErrorClassifier.GenerationError classifyGenerationError(String errorMessage) {
        return GenerationErrorClassifier.classify(errorMessage);
    }

    private void resetResidualGenerationState(Long appId) {
        GenerationSession session = activeGenerationSessions.get(appId);
        if (session == null) {
            return;
        }
        if (!session.isActive()) {
            activeGenerationSessions.remove(appId, session);
        }
    }

    private Object getGenerationLock(Long appId) {
        return generationLocks.computeIfAbsent(appId, key -> new Object());
    }

    private GenerationPreparation prepareGeneration(App app, String userMessage) {
        GenerationIntent intent = recognizeGenerationIntent(app, userMessage);
        GenerationContextAssembly contextAssembly = assembleGenerationContext(intent);
        GenerationRoutingPlan routingPlan = routeGeneration(intent, contextAssembly);
        return buildGenerationPreparation(intent, routingPlan);
    }

    private GenerationIntent recognizeGenerationIntent(App app, String userMessage) {
        CodeGenTypeEnum currentType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        ThrowUtils.throwIf(currentType == null, ErrorCode.PARAMS_ERROR, "应用代码生成类型错误");
        return new GenerationIntent(
                app,
                currentType,
                appDatabaseResourceService.appendGenerationInstructionIfEnabled(app, userMessage),
                determineGeneratingStage(app),
                hasGeneratedCode(app)
        );
    }

    private GenerationContextAssembly assembleGenerationContext(GenerationIntent intent) {
        return new GenerationContextAssembly(createProjectContextSupplier(intent.app()));
    }

    private GenerationRoutingPlan routeGeneration(GenerationIntent intent, GenerationContextAssembly contextAssembly) {
        return new GenerationRoutingPlan(createRoutingFunction(intent.app(), intent.currentType()), contextAssembly);
    }

    private GenerationPreparation buildGenerationPreparation(GenerationIntent intent, GenerationRoutingPlan routingPlan) {
        CodeGenTypeEnum targetType = routingPlan.routingFunction().apply(intent.generationMessage());
        String memoryContext = generationMemoryContextService.buildGenerationMemoryContext(
                intent.app(),
                intent.generationMessage(),
                targetType
        );
        GenerationOrchestrationResult orchestrationResult = generationOrchestrator.prepare(
                new GenerationOrchestrationRequest(
                        intent.app(),
                        intent.generationMessage(),
                        intent.currentType(),
                        intent.generatingStage(),
                        intent.hasGeneratedCode(),
                        routingPlan.contextAssembly().projectContextSupplier(),
                        routingPlan.routingFunction(),
                        memoryContext
                )
        );
        GenerationPreparation preparation = new GenerationPreparation(
                orchestrationResult.originalType(),
                orchestrationResult.targetType(),
                orchestrationResult.upgradeRequired(),
                orchestrationResult.generatingStage(),
                orchestrationResult.enhancedMessage(),
                orchestrationResult.events(),
                orchestrationResult.artifacts(),
                orchestrationResult.qualityGateResult(),
                orchestrationResult.timings(),
                orchestrationResult.taskId()
        );
        bindToolExecutionContext(intent.app(), preparation);
        return preparation;
    }

    private void bindToolExecutionContext(App app, GenerationPreparation preparation) {
        if (app == null || app.getId() == null || preparation == null) {
            return;
        }
        GenerationArtifact changePlanArtifact = preparation.artifact("change_plan");
        ChangePlan changePlan = changePlanArtifact == null ? null : ChangePlan.fromPayload(changePlanArtifact.payload());
        boolean allowUnplannedWrite = changePlan != null && "project_bootstrap".equals(changePlan.changeScope());
        String generationMode = allowUnplannedWrite ? "full_generation" : "patch_first";
        generationToolExecutionContextService.bindChangePlan(
                app.getId(),
                preparation.taskId(),
                generationMode,
                preparation.targetType(),
                changePlan,
                allowUnplannedWrite,
                "orchestration_context"
        );
    }

    private CodeGenTypeEnum routeCodeGenTypeForPrompt(App app, String routingPrompt, CodeGenTypeEnum currentType) {
        try {
            AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
            CodeGenTypeEnum routedType = routingService.routeCodeGenType(routingPrompt);
            return routedType == null ? currentType : routedType;
        } catch (Exception e) {
            log.warn("生成前重新路由失败，沿用当前模式，appId: {}", app.getId(), e);
            return currentType;
        }
    }

    private Supplier<String> createProjectContextSupplier(App app) {
        return () -> buildProjectContext(app);
    }

    private Function<String, CodeGenTypeEnum> createRoutingFunction(App app, CodeGenTypeEnum currentType) {
        Map<String, CodeGenTypeEnum> routingCache = new ConcurrentHashMap<>();
        return routingPrompt -> routingCache.computeIfAbsent(
                routingPrompt,
                key -> routeCodeGenTypeForPrompt(app, key, currentType)
        );
    }

    private String determineGeneratingStage(App app) {
        if (app == null || app.getId() == null) {
            return AppConstant.GENERATING_STAGE_CREATE;
        }
        return hasGeneratedCode(app) ? AppConstant.GENERATING_STAGE_UPDATE : AppConstant.GENERATING_STAGE_CREATE;
    }

    private boolean hasGeneratedCode(App app) {
        try {
            getCodeRootDir(app);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    private void updateGenerationPhase(Long appId, String generatingStage, String generatingMessage) {
        markGenerationStage(appId, generatingStage, generatingMessage);
    }

    private void markGenerationStage(Long appId, String generatingStage, String generatingMessage) {
        generationAppStateService.markGenerationStage(appId, generatingStage, generatingMessage, activeGenerationSessions.get(appId));
    }

    private void updateGenerationSnapshotIfDue(Long appId, StringBuilder generatedContent, long[] lastSnapshotUpdateAt) {
        long now = System.currentTimeMillis();
        if (now - lastSnapshotUpdateAt[0] < GENERATION_SNAPSHOT_UPDATE_INTERVAL_MILLIS) {
            return;
        }
        lastSnapshotUpdateAt[0] = now;
        generationAppStateService.updateGenerationSnapshot(appId, generatedContent.toString());
    }

    private void appendGenerationSnapshotChunk(StringBuilder generatedContent, String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return;
        }
        generatedContent.append(chunk);
        int overflowChars = generatedContent.length() - MAX_GENERATION_SNAPSHOT_CHARS;
        if (overflowChars > 0) {
            generatedContent.delete(0, overflowChars);
        }
    }

    private void completeGenerationSession(GenerationSession session,
                                           GenerationPreparation preparation,
                                           String status) {
        if (session == null || !session.tryMarkCompleted()) {
            return;
        }
        recordUserWaitMetric(session, preparation, status);
        generationTraceService.updateMemorySummary(preparation.taskId(), buildMemorySummary(preparation, status));
        generationTraceService.completeTask(preparation.taskId(), status, session.startedAt(), null);
        userCreditService.chargeGenerationTask(preparation.taskId());
        session.complete();
    }

    /**
     * 延迟清理轻量编辑的 session，确保前端有时间获取事件流。
     * 使用 Sinks.Many.replay() 的特性，即使 session 完成后，前端仍可获取历史事件。
     */
    private void scheduleSessionCleanup(Long appId, GenerationSession session) {
        // 30 秒后清理 session，给前端足够时间建立连接并获取事件
        CompletableFuture.delayedExecutor(30, TimeUnit.SECONDS).execute(() -> {
            activeGenerationSessions.remove(appId, session);
            log.debug("轻量编辑 session 已清理，appId: {}", appId);
        });
    }

    private String buildMemorySummary(GenerationPreparation preparation, String status) {
        if (preparation == null) {
            return "";
        }
        List<String> lines = new ArrayList<>();
        lines.add("任务状态：" + StrUtil.blankToDefault(status, "unknown"));
        lines.add("生成类型：" + (preparation.targetType() == null ? "unknown" : preparation.targetType().getValue())
                + "，阶段：" + StrUtil.blankToDefault(preparation.generatingStage(), "unknown")
                + "，构建校验：" + preparation.requiresBuildValidation());
        GenerationArtifact changePlan = preparation.artifact("change_plan");
        if (changePlan != null) {
            lines.add("变更计划：" + compactMemoryText(String.valueOf(changePlan.payload()), 900));
        }
        GenerationArtifact diffSummary = preparation.artifact("diff_summary");
        if (diffSummary != null) {
            lines.add("实际变更：" + compactMemoryText(String.valueOf(diffSummary.payload()), 900));
        }
        GenerationArtifact patchResult = preparation.artifact("patch_result");
        if (patchResult != null) {
            lines.add("Patch 结果：" + compactMemoryText(String.valueOf(patchResult.payload()), 700));
        }
        if (preparation.qualityGateResult() != null) {
            lines.add("质量门禁：passed=" + preparation.qualityGateResult().passed()
                    + ", blockers=" + compactMemoryText(String.valueOf(preparation.qualityGateResult().blockers()), 500));
        }
        return compactMemoryText(String.join("\n", lines), 5000);
    }

    private String compactMemoryText(String value, int maxLength) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private void recordUserWaitMetric(GenerationSession session,
                                      GenerationPreparation preparation,
                                      String status) {
        if (session == null || preparation == null) {
            return;
        }
        long orchestrationDurationMs = preparation.timings() == null
                ? 0L
                : preparation.timings().values().stream().mapToLong(Long::longValue).sum();
        generationOrchestrationMetricsCollector.recordUserWaitDuration(
                orchestrationMode(preparation),
                preparation.targetType() == null ? "unknown" : preparation.targetType().getValue(),
                status,
                Duration.between(session.startedAt(), Instant.now()).plusMillis(Math.max(0L, orchestrationDurationMs))
        );
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        if (preparation == null || preparation.events() == null) {
            return "unknown";
        }
        return preparation.events().stream()
                .map(GenerationStreamEvent::getData)
                .filter(map -> map != null && map.get("orchestrationMode") != null)
                .map(map -> String.valueOf(map.get("orchestrationMode")))
                .findFirst()
                .orElse("unknown");
    }

    private void rollbackCodeGenTypeIfNeeded(Long appId, GenerationPreparation preparation) {
        if (preparation == null || !preparation.upgradeRequired()) {
            return;
        }
        cleanupCodeDir(appId, preparation.targetType());
        generationAppStateService.switchAppCodeGenType(appId, preparation.originalType());
    }

    private void cleanupCodeDir(Long appId, CodeGenTypeEnum codeGenTypeEnum) {
        if (appId == null || appId <= 0 || codeGenTypeEnum == null) {
            return;
        }
        File codeDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + codeGenTypeEnum.getValue() + "_" + appId);
        if (!codeDir.exists()) {
            return;
        }
        try {
            File canonicalRoot = new File(AppConstant.CODE_OUTPUT_ROOT_DIR).getCanonicalFile();
            File canonicalDir = codeDir.getCanonicalFile();
            if (!canonicalDir.toPath().startsWith(canonicalRoot.toPath())) {
                log.warn("跳过清理非法代码目录，appId: {}, dir: {}", appId, canonicalDir.getAbsolutePath());
                return;
            }
            FileUtil.del(canonicalDir);
        } catch (Exception e) {
            log.warn("清理升级失败目录时发生异常，appId: {}, type: {}", appId, codeGenTypeEnum.getValue(), e);
        }
    }

    private File getCodeRootDir(App app) {
        String sourceDirName = app.getCodeGenType() + "_" + app.getId();
        File rootDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName);
        ThrowUtils.throwIf(!rootDir.exists() || !rootDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        return rootDir;
    }

    private String buildProjectContext(App app) {
        try {
            File rootDir = getCodeRootDir(app);
            CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenTypeEnum == null) {
                return "";
            }
            String projectIndex = buildProjectIndex(rootDir);
            String keyFiles = switch (codeGenTypeEnum) {
                case HTML -> readSingleFileContext(rootDir, "index.html");
                case MULTI_FILE -> readMultiFileContext(rootDir, List.of("index.html", "style.css", "script.js"));
                case VUE_PROJECT -> readMultiFileContext(rootDir, List.of("src/App.vue", "src/main.js", "src/main.ts", "index.html"));
                case BACKEND_PROJECT -> readMultiFileContext(rootDir, List.of("go.mod", "cmd/server/main.go", "internal/config/config.go", "internal/database/database.go", "sql/schema.sql"));
                case FULL_STACK_PROJECT -> readMultiFileContext(rootDir, List.of("frontend/package.json", "frontend/src/services/request.ts", "frontend/src/App.vue", "backend/go.mod", "backend/cmd/server/main.go", "backend/internal/config/config.go", "backend/sql/schema.sql", ".env.example"));
            };
            if (StrUtil.isBlank(projectIndex)) {
                return keyFiles;
            }
            if (StrUtil.isBlank(keyFiles)) {
                return projectIndex;
            }
            return projectIndex + "\n\n" + keyFiles;
        } catch (Exception e) {
            log.warn("构建项目上下文失败，appId: {}, error: {}", app.getId(), e.getMessage());
            return "";
        }
    }

    private String buildProjectIndex(File rootDir) {
        List<String> indexedFiles = new ArrayList<>();
        try {
            FileUtil.walkFiles(rootDir, file -> {
                if (indexedFiles.size() >= MAX_PROJECT_INDEX_FILES) {
                    return;
                }
                if (shouldHideFile(file)) {
                    return;
                }
                String relativePath = normalizeRelativePath(rootDir, file);
                if (file.isDirectory()) {
                    return;
                }
                String extension = FileUtil.extName(file).toLowerCase();
                if (isIndexableProjectFile(relativePath, extension)) {
                    indexedFiles.add(relativePath);
                }
            });
        } catch (Exception e) {
            log.warn("构建项目索引失败: {}", e.getMessage());
        }
        if (indexedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("项目索引:\n");
        indexedFiles.stream()
                .sorted()
                .limit(MAX_PROJECT_INDEX_FILES)
                .forEach(path -> builder.append("- ").append(path).append('\n'));
        return builder.toString().trim();
    }

    private boolean isIndexableProjectFile(String relativePath, String extension) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        if (relativePath.startsWith("src/") || relativePath.startsWith("public/") || relativePath.startsWith("cmd/") || relativePath.startsWith("internal/") || relativePath.startsWith("sql/") || relativePath.startsWith("frontend/") || relativePath.startsWith("backend/")) {
            return Set.of("vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md", "go", "sql", "mod", "sum", "yml", "yaml").contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html", "tsconfig.json", "tsconfig.app.json", "go.mod", "go.sum", "README.md", "docker-compose.yml", ".env.example")
                .contains(relativePath);
    }

    private String readSingleFileContext(File rootDir, String relativePath) {
        File file = new File(rootDir, relativePath);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        return "当前文件: " + relativePath + "\n```html\n" + truncateForModel(content) + "\n```";
    }

    private String readMultiFileContext(File rootDir, List<String> relativePaths) {
        List<String> sections = new ArrayList<>();
        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            String extension = FileUtil.extName(file);
            String content = FileUtil.readString(file, StandardCharsets.UTF_8);
            sections.add("当前文件: " + relativePath + "\n```" + extension + "\n" + truncateForModel(content) + "\n```");
        }
        return String.join("\n\n", sections);
    }

    private String truncateForModel(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_MODEL_CONTEXT_FILE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_MODEL_CONTEXT_FILE_CHARS)
                + "\n<!-- 文件内容过长，以上为截断后的前 "
                + MAX_MODEL_CONTEXT_FILE_CHARS
                + " 个字符 -->";
    }

    private boolean shouldHideFile(File file) {
        return GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(file.getName());
    }

    private String normalizeRelativePath(File rootDir, File file) {
        try {
            Path rootPath = rootDir.getCanonicalFile().toPath();
            Path filePath = file.getCanonicalFile().toPath();
            return rootPath.relativize(filePath).toString().replace(File.separator, "/");
        } catch (Exception e) {
            return file.getName();
        }
    }

    /**
     * 尝试使用模板 slot 填充进行首次生成。
     *
     * @param app     应用
     * @param request 生成任务请求
     * @return slot 填充结果，如果无法使用则返回 null
     */
    private SlotFillResult trySlotFillGeneration(App app, GenerationTaskRequest request) {
        if (app == null || request == null) {
            return null;
        }

        // 1. 判断是否是 Vue 项目首次生成
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT) {
            log.debug("非 Vue 项目，跳过 slot 填充: {}", codeGenType);
            return null;
        }

        // 2. 选择模板
        String templateId = vueProjectTemplateBootstrapService.selectTemplateId(request.message());
        if (StrUtil.isBlank(templateId)) {
            log.debug("无法选择模板");
            return null;
        }

        // 3. 检查模板是否支持 slot 填充
        if (!templateSlotFillService.supportsSlotFill(templateId)) {
            log.debug("模板不支持 slot 填充: {}", templateId);
            return null;
        }

        // 4. 并行执行模板复制和 slot 填充
        ParallelSlotFillService.ParallelSlotFillResult parallelResult =
                parallelSlotFillService.executeInParallel(templateId, app.getId(), request.message());

        if (!parallelResult.success()) {
            log.debug("并行 Slot Fill 失败，回退到串行执行");
            return trySlotFillGenerationSequential(app, request, templateId);
        }

        SlotFillResult result = parallelResult.slotFillResult();
        if (result == null || result.patchOperations() == null || result.patchOperations().isEmpty()) {
            log.debug("Slot 填充未产生有效操作");
            return null;
        }

        // 5. 应用 patch 操作
        try {
            String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + codeGenType.getValue() + "_" + app.getId();
            Path projectRoot = Path.of(projectPath);
            generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(),
                    "slot_fill_" + System.currentTimeMillis(),
                    projectRoot,
                    result.patchOperations(),
                    "slot_fill_generation"
            );
            log.info("Slot 填充 patch 应用成功: {} 个操作", result.patchOperations().size());
        } catch (Exception e) {
            log.warn("Slot 填充 patch 应用失败: {}", e.getMessage());
            return null;
        }

        // 6. 发布事件
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用模板 slot 填充路径", Map.of(
                "route", SLOT_FILL_ROUTE,
                "templateId", templateId,
                "filledSlots", result.filledSlots(),
                "totalChars", result.totalChars()
        ));

        return result;
    }

    /**
     * 串行执行 slot 填充（回退方案）。
     */
    private SlotFillResult trySlotFillGenerationSequential(App app, GenerationTaskRequest request, String templateId) {
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());

        // 引导模板
        VueProjectTemplateBootstrapService.BootstrapResult bootstrapResult =
                vueProjectTemplateBootstrapService.bootstrapIfNecessary(app.getId(), request.message());
        if (!bootstrapResult.bootstrapped()) {
            log.debug("模板引导跳过: {}", bootstrapResult.reason());
            if ("workspace_exists".equals(bootstrapResult.reason())) {
                return null;
            }
        }

        // 执行 slot 填充
        SlotFillResult result = templateSlotFillService.fillSlots(templateId, app.getId(), request.message());
        if (result == null || result.patchOperations() == null || result.patchOperations().isEmpty()) {
            log.debug("Slot 填充未产生有效操作");
            return null;
        }

        // 应用 patch 操作
        try {
            String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator
                    + codeGenType.getValue() + "_" + app.getId();
            Path projectRoot = Path.of(projectPath);
            generationPatchApplyService.applyWithoutChangePlan(
                    app.getId(),
                    "slot_fill_" + System.currentTimeMillis(),
                    projectRoot,
                    result.patchOperations(),
                    "slot_fill_generation"
            );
            log.info("Slot 填充 patch 应用成功: {} 个操作", result.patchOperations().size());
        } catch (Exception e) {
            log.warn("Slot 填充 patch 应用失败: {}", e.getMessage());
            return null;
        }

        // 发布事件
        generationEventPublisher.publish(request, GenerationEventType.TASK_ROUTE, "使用模板 slot 填充路径", Map.of(
                "route", SLOT_FILL_ROUTE,
                "templateId", templateId,
                "filledSlots", result.filledSlots(),
                "totalChars", result.totalChars()
        ));

        return result;
    }

    private record GenerationIntent(App app,
                                    CodeGenTypeEnum currentType,
                                    String generationMessage,
                                    String generatingStage,
                                    boolean hasGeneratedCode) {
    }

    private record GenerationContextAssembly(Supplier<String> projectContextSupplier) {
    }

    private record GenerationRoutingPlan(Function<String, CodeGenTypeEnum> routingFunction,
                                         GenerationContextAssembly contextAssembly) {
    }

    private final class MissingGeneratedProjectException extends BusinessException {

        private MissingGeneratedProjectException(GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
            super(ErrorCode.SYSTEM_ERROR, buildMissingProjectCodeMessage(workspaceState));
        }
    }
}
