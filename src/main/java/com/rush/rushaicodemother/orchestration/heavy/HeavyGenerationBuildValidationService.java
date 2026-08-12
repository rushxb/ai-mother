package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.devserver.DevServerValidationRequest;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/**
 * 重型生成构建校验服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeavyGenerationBuildValidationService {

    private final DevServerValidationService devServerValidationService;
    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationPerformanceMonitorService generationPerformanceMonitorService;
    private final HeavyGenerationExecutionService heavyGenerationExecutionService;
    private final HeavyGenerationFailureRecoveryService heavyGenerationFailureRecoveryService;
    private final HeavyGenerationSessionCompletionService heavyGenerationSessionCompletionService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationProjectBuildValidationService projectBuildValidationService;
    private final GenerationStageAdmissionService generationStageAdmissionService;
    private final GenerationPreviewMilestoneService generationPreviewMilestoneService;

    /**
 * 运行并{@code Auto}{@code Repair}处理流程。
 *
 * @param appId 应用编号
 * @param loginUser 当前登录用户
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean runWithAutoRepair(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session) {
        return runWithAutoRepair(
                appId,
                loginUser,
                preparation,
                session,
                GenerationVerificationPolicy.legacy()
        );
    }

    /** 按统一验证策略执行构建、运行时验证和自动修复。 */
    public boolean runWithAutoRepair(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session,
                                     GenerationVerificationPolicy verificationPolicy) {
        Objects.requireNonNull(verificationPolicy, "生成验证策略不能为空");
        GenerationWorkspace workspace = resolveExecutionWorkspace(appId, preparation.targetType(), session);
        StringBuilder generatedContent = new StringBuilder();
        long[] lastSnapshotUpdateAt = {0L};
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                inspectWorkspace(workspace, preparation.targetType());
        if (!workspaceState.canAutoRepair()) {
            heavyGenerationFailureRecoveryService.emitMissingProjectCode(appId, preparation, session, workspaceState);
            return false;
        }
        generationStageAdmissionService.requireBuild(
                session,
                preparation,
                orchestrationMode(preparation)
        );
        ProjectBuildValidationResult buildResult = executeBuild(appId, preparation, workspace);
        if (session.isCancelled()) {
            return false;
        }
        int availableRepairRounds = session.remainingBudget(GenerationBudgetKind.REPAIR_ROUND);
        emitBuildResult(session, preparation, buildResult, Map.of(
                "willAutoRepair", !buildResult.success()
                        && workspaceState.canAutoRepair()
                         && availableRepairRounds > 0
                        && generationStageAdmissionService.canRepair(session, preparation)
        ));
        ValidationFailure validationFailure;
        if (buildResult.success()) {
            DevServerValidationResult runtimeResult = validateRuntimeIfNeeded(
                    appId,
                    loginUser,
                    preparation,
                    session,
                    verificationPolicy,
                    "构建通过，正在验证 Dev Server 运行时..."
            );
            if (session.isCancelled()) {
                return false;
            }
            if (runtimeResult == null || runtimeResult.isPassed()) {
                GenerationVerificationEvidenceRecorder.recordPassed(
                        preparation, verificationPolicy, "heavy_build_validation");
                return true;
            }
            validationFailure = ValidationFailure.runtime(runtimeResult);
        } else {
            validationFailure = ValidationFailure.build(buildResult);
        }
        if (availableRepairRounds <= 0 || !workspaceState.canAutoRepair()) {
            heavyGenerationFailureRecoveryService.emitBuildFailure(
                    appId, preparation, session, validationFailure.publicSummary());
            return false;
        }
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (int round = 1; round <= availableRepairRounds; round++) {
            session.throwIfCancelled();
            if (!generationStageAdmissionService.allowRepair(
                    session, preparation, orchestrationMode(preparation), validationFailure.validationStage())) {
                heavyGenerationFailureRecoveryService.emitBuildFailure(
                        appId, preparation, session, validationFailure.publicSummary());
                return false;
            }
            workspaceState = inspectWorkspace(workspace, preparation.targetType());
            if (!workspaceState.canAutoRepair()) {
                heavyGenerationFailureRecoveryService.emitMissingProjectCode(appId, preparation, session, workspaceState);
                return false;
            }
            session.consumeBudget(GenerationBudgetKind.REPAIR_ROUND);
            String repairStage = validationFailure.validationStage();
            markGenerationStage(
                    appId,
                    AppConstant.GENERATING_STAGE_REPAIR,
                    validationFailure.repairStageMessage(),
                    session
            );
            generationOrchestrationMetricsCollector.recordAutoRepair(
                    orchestrationMode(preparation), repairStage, "started");
            session.emit(GenerationStreamEvent.repairStart(
                    "\n\n[自动修复] 第 " + round + " 轮修复开始\n\n",
                    validationFailure.repairEventData(round, availableRepairRounds, preparation.taskId())
            ));
            try {
                heavyGenerationExecutionService.executeGenerationRound(
                        appId,
                        loginUser,
                        preparation.targetType(),
                        heavyGenerationExecutionService.buildAutoRepairPrompt(
                                appId,
                                preparation,
                                validationFailure.toRepairException(),
                                round
                        ),
                        session,
                        generatedContent,
                        lastSnapshotUpdateAt
                );
            } catch (Exception exception) {
                generationOrchestrationMetricsCollector.recordAutoRepair(
                        orchestrationMode(preparation), repairStage, "failed");
                throw exception;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "自动修复完成，正在重新构建校验...", session);
            generationStageAdmissionService.requireBuild(
                    session,
                    preparation,
                    orchestrationMode(preparation)
            );
            buildResult = executeBuild(appId, preparation, workspace);
            if (session.isCancelled()) {
                return false;
            }
            emitBuildResult(session, preparation, buildResult, Map.of());
            if (buildResult.success()) {
                DevServerValidationResult runtimeResult = validateRuntimeIfNeeded(
                        appId,
                        loginUser,
                        preparation,
                        session,
                        verificationPolicy,
                        "修复后构建通过，正在验证 Dev Server 运行时..."
                );
                if (session.isCancelled()) {
                    return false;
                }
                if (runtimeResult == null || runtimeResult.isPassed()) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(
                            orchestrationMode(preparation), repairStage, "success");
                    GenerationVerificationEvidenceRecorder.recordPassed(
                            preparation, verificationPolicy, "heavy_build_repair_validation");
                    return true;
                }
                validationFailure = ValidationFailure.runtime(runtimeResult);
            } else {
                validationFailure = ValidationFailure.build(buildResult);
            }
            generationOrchestrationMetricsCollector.recordAutoRepair(
                    orchestrationMode(preparation), repairStage, "failed");
        }
        heavyGenerationFailureRecoveryService.emitBuildFailure(
                appId, preparation, session, validationFailure.publicSummary());
        return false;
    }

    /** 执行构建处理流程。 */
    private ProjectBuildValidationResult executeBuild(
            Long appId,
            GenerationPreparation preparation,
            GenerationWorkspace workspace
    ) {
        ProjectBuildValidationResult buildResult = projectBuildValidationService.validate(
                workspace,
                preparation.targetType(),
                preparation.taskId()
        );
        if (buildResult != null) {
            return buildResult;
        }
        IllegalStateException contractViolation =
                new IllegalStateException("项目构建门禁返回了空结果");
        log.error("项目构建门禁违反非空结果契约，appId: {}, taskId: {}",
                appId, preparation.taskId(), contractViolation);
        throw new BusinessException(
                ErrorCode.SYSTEM_ERROR,
                "项目构建服务异常，请稍后重试",
                contractViolation
        );
    }

    private GeneratedProjectWorkspaceInspector.WorkspaceState inspectWorkspace(
            GenerationWorkspace workspace,
            CodeGenTypeEnum targetType
    ) {
        return switch (targetType) {
            case VUE_PROJECT -> GeneratedProjectWorkspaceInspector.inspectVueProject(
                    workspace.frontendRootPath());
            case BACKEND_PROJECT -> GeneratedProjectWorkspaceInspector.inspectBackendProject(
                    workspace.backendRootPath());
            case FULL_STACK_PROJECT -> GeneratedProjectWorkspaceInspector.inspectFullStackProject(
                    workspace.canonicalRootPath());
            default -> throw new IllegalArgumentException("当前项目类型不支持构建门禁: " + targetType.getValue());
        };
    }

    private GenerationWorkspace resolveExecutionWorkspace(Long appId,
                                                           CodeGenTypeEnum targetType,
                                                           GenerationSession session) {
        if (session != null && session.executionWorkspace() != null
                && session.executionWorkspace().appId().equals(appId)
                && session.executionWorkspace().codeGenType() == targetType) {
            return session.executionWorkspace().workspace();
        }
        return generationWorkspaceService.resolve(appId, targetType);
    }

    /** 校验{@code ate}运行时{@code If}{@code Needed}是否有效。 */
    private DevServerValidationResult validateRuntimeIfNeeded(Long appId,
                                                              User loginUser,
                                                              GenerationPreparation preparation,
                                                              GenerationSession session,
                                                              GenerationVerificationPolicy verificationPolicy,
                                                              String stageMessage) {
        // BUILD 只执行构建门禁；EXPERT 才进入现有 Dev Server 运行时验证。
        if (!verificationPolicy.requiresRuntimeValidation(preparation.targetType())) {
            return null;
        }
        generationStageAdmissionService.requireRuntimeValidation(
                session,
                preparation,
                orchestrationMode(preparation)
        );
        markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, stageMessage, session);
        GenerationPerformanceMonitorService.SpanTimer span =
                generationPerformanceMonitorService.startSpan(preparation.taskId(), "dev_server_validation", GenerationSpanCategory.VALIDATION);
        DevServerValidationResult dsResult;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            if (session.executionContext() == null || session.executionContext().executionFence() == null) {
                dsResult = devServerValidationService.validate(
                        preparation.taskId(), appId, loginUser.getId(), preparation.targetType());
            } else {
                // Dev Server 就绪即证明工作区可渲染，是「用户可以先看到东西」的最早诚实信号。
                // 回调在服务仍运行的窗口内触发；只发暂定预览事件，不构成完成证据、不计费、不写终态。
                //
                // 这里把会话持有权交给生成任务：验证返回后 Dev Server 继续运行，用户才真的点得开暂定预览。
                // 停止责任随之移交给生成任务：发布前由 GenerationWorkspaceReleaseService 停止，
                // 失败、取消和超时由 GenerationTaskFinalizer 按 execution fence 精确停止。
                dsResult = devServerValidationService.validate(
                        DevServerValidationRequest
                                .of(preparation.taskId(), appId, loginUser.getId(), preparation.targetType())
                                .withExecutionFence(session.executionContext().executionFence())
                                .withReadyCallback(() ->
                                        publishProvisionalPreviewSafely(session, preparation.targetType()))
                                .withTaskScopedOwnership());
            }
        } catch (RuntimeException exception) {
            span.failed(LogExceptionSanitizer.sanitizeMessage(exception));
            throw exception;
        }
        if (dsResult == null) {
            dsResult = DevServerValidationResult.startupFailed(
                    preparation.taskId(), appId, 0, "运行时验证服务未返回结果");
        }
        generationOrchestrationMetricsCollector.recordRuntimeValidation(
                orchestrationMode(preparation),
                preparation.targetType().getValue(),
                dsResult.status().name()
        );
        if (dsResult.isPassed()) {
            span.success();
        } else {
            span.failed(dsResult.summary());
        }
        Map<String, Object> eventData = new java.util.LinkedHashMap<>(dsResult.toEventData());
        eventData.put("willAutoRepair", !dsResult.isPassed()
                && session.remainingBudget(GenerationBudgetKind.REPAIR_ROUND) > 0
                && generationStageAdmissionService.canRepair(session, preparation));
        session.emit(GenerationStreamEvent.devServerValidation(dsResult.summary(), eventData));
        if (!dsResult.isPassed()) {
            log.warn("Dev Server 运行时验证失败，appId: {}, summary: {}", appId, dsResult.summary());
        }
        return dsResult;
    }

    /**
     * 发布暂定预览里程碑，失败不影响交付。
     *
     * <p>暂定预览纯属体验增强，任何异常都不得冒泡打断验证与发布链路。</p>
     */
    private void publishProvisionalPreviewSafely(GenerationSession session, CodeGenTypeEnum targetType) {
        try {
            generationPreviewMilestoneService.publishProvisionalReady(session, targetType);
        } catch (RuntimeException exception) {
            log.warn("暂定预览里程碑通知失败，生成流程继续执行，targetType: {}",
                    targetType == null ? "unknown" : targetType.getValue(),
                    LogExceptionSanitizer.sanitize(exception));
        }
    }

    private record ValidationFailure(
            String validationStage,
            String status,
            String failureKind,
            String publicSummary,
            String repairDiagnostic
    ) {

        /** 构建并返回校验失败。 */
        private static ValidationFailure build(ProjectBuildValidationResult result) {
            String summary = result.failureSummary();
            String diagnostic = """
                    validationStage=build
                    status=FAILED
                    failureKind=BUILD_FAILURE
                    component=%s
                    buildStage=%s
                    publicSummary=%s
                    buildDiagnostics:
                    %s
                    """.formatted(
                    result.component(),
                    result.stage(),
                    summary,
                    result.report()
            ).trim();
            return new ValidationFailure("build", "FAILED", "BUILD_FAILURE", summary, diagnostic);
        }

        private static ValidationFailure runtime(DevServerValidationResult result) {
            return new ValidationFailure(
                    "runtime",
                    result.status().name(),
                    result.failureKind().name(),
                    result.summary(),
                    result.toPublicRepairDiagnostic()
            );
        }

        private BusinessException toRepairException() {
            return new BusinessException(ErrorCode.SYSTEM_ERROR, repairDiagnostic);
        }

        private String repairStageMessage() {
            return "runtime".equals(validationStage)
                    ? "运行时验证未通过，正在自动修复..."
                    : "构建未通过，正在自动修复...";
        }

        private Map<String, Object> repairEventData(int round, int maxRounds, String taskId) {
            return Map.of(
                    "round", round,
                    "maxRounds", maxRounds,
                    "taskId", taskId,
                    "agent", "runtime".equals(validationStage) ? "RuntimeFix" : "BuildFix",
                    "validationStage", validationStage,
                    "status", status,
                    "failureKind", failureKind
            );
        }
    }

    /** 发送构建结果事件。 */
    private void emitBuildResult(GenerationSession session,
                                 GenerationPreparation preparation,
                                 ProjectBuildValidationResult buildResult,
                                 Map<String, Object> extraData) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        String publicReport = buildResult.report();
        if (extraData != null) {
            data.putAll(extraData);
        }
        data.put("success", buildResult.success());
        data.put("component", buildResult.component());
        data.put("stage", buildResult.stage());
        data.put("projectPath", buildResult.projectPath());
        data.put("summary", buildResult.summary());
        data.put("report", publicReport);
        data.put("taskId", preparation.taskId());
        data.put("qualityGate", preparation.qualityGateLevel());
        session.emit(GenerationStreamEvent.buildResult(publicReport, data));
    }

    private void markGenerationStage(Long appId,
                                     String generatingStage,
                                     String generatingMessage,
                                     GenerationSession session) {
        if (session == null || session.preparation() == null) {
            throw new IllegalStateException("heavy generation session preparation is required");
        }
        generationTaskLifecycleService.updateGenerationStage(
                session.preparation().taskId(), appId, generatingStage, generatingMessage);
    }

    private String orchestrationMode(GenerationPreparation preparation) {
        return heavyGenerationSessionCompletionService.orchestrationMode(preparation);
    }
}
