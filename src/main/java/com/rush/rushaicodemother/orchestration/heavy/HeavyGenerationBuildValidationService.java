package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Map;

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
    private final VueProjectBuilder vueProjectBuilder;

    public boolean runWithAutoRepair(Long appId,
                                     User loginUser,
                                     GenerationPreparation preparation,
                                     GenerationSession session) {
        GenerationWorkspace workspace = generationWorkspaceService.resolve(appId, preparation.targetType());
        Path projectPath = workspace.frontendRootPath();
        StringBuilder generatedContent = new StringBuilder();
        long[] lastSnapshotUpdateAt = {0L};
        GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState =
                GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
        if (!workspaceState.canAutoRepair()) {
            heavyGenerationFailureRecoveryService.emitMissingProjectCode(appId, preparation, session, workspaceState);
            return false;
        }
        VueBuildResult buildResult = executeBuild(appId, preparation, projectPath);
        if (session.isCancelled()) {
            return false;
        }
        int availableRepairRounds = session.remainingBudget(GenerationBudgetKind.REPAIR_ROUND);
        emitBuildResult(session, preparation, buildResult, Map.of(
                "willAutoRepair", !buildResult.success()
                        && workspaceState.canAutoRepair()
                        && availableRepairRounds > 0
        ));
        if (buildResult.success()) {
            return validateRuntimeIfNeeded(
                    appId,
                    loginUser,
                    preparation,
                    session,
                    "构建通过，正在验证 Dev Server 运行时..."
            );
        }
        if (availableRepairRounds <= 0 || !workspaceState.canAutoRepair()) {
            heavyGenerationFailureRecoveryService.emitBuildFailure(
                    appId, preparation, session, buildResult.toPublicFailureSummary());
            return false;
        }
        for (int round = 1; round <= availableRepairRounds; round++) {
            session.throwIfCancelled();
            workspaceState = GeneratedProjectWorkspaceInspector.inspectVueProject(projectPath);
            if (!workspaceState.canAutoRepair()) {
                heavyGenerationFailureRecoveryService.emitMissingProjectCode(appId, preparation, session, workspaceState);
                return false;
            }
            session.consumeBudget(GenerationBudgetKind.REPAIR_ROUND);
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_REPAIR, "构建未通过，正在自动修复...", session);
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "started");
            session.emit(GenerationStreamEvent.repairStart("\n\n[自动修复] 第 " + round + " 轮修复开始\n\n", Map.of(
                    "round", round,
                    "maxRounds", availableRepairRounds,
                    "taskId", preparation.taskId(),
                    "agent", "BuildFix"
            )));
            try {
                heavyGenerationExecutionService.executeGenerationRound(
                        appId,
                        loginUser,
                        preparation.targetType(),
                        heavyGenerationExecutionService.buildAutoRepairPrompt(
                                appId,
                                preparation,
                                new BusinessException(ErrorCode.SYSTEM_ERROR, buildResult.toPublicFailureSummary()),
                                round
                        ),
                        session,
                        generatedContent,
                        lastSnapshotUpdateAt
                );
            } catch (Exception exception) {
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
                throw exception;
            }
            markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, "自动修复完成，正在重新构建校验...", session);
            buildResult = executeBuild(appId, preparation, projectPath);
            if (session.isCancelled()) {
                return false;
            }
            emitBuildResult(session, preparation, buildResult, Map.of());
            if (buildResult.success()) {
                boolean runtimePassed = validateRuntimeIfNeeded(
                        appId,
                        loginUser,
                        preparation,
                        session,
                        "修复后构建通过，正在验证 Dev Server 运行时..."
                );
                if (runtimePassed) {
                    generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "success");
                    return true;
                }
                generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
                continue;
            }
            generationOrchestrationMetricsCollector.recordAutoRepair(orchestrationMode(preparation), "build", "failed");
        }
        heavyGenerationFailureRecoveryService.emitBuildFailure(
                appId, preparation, session, buildResult.toPublicFailureSummary());
        return false;
    }

    private VueBuildResult executeBuild(Long appId,
                                        GenerationPreparation preparation,
                                        Path projectPath) {
        VueBuildResult buildResult = vueProjectBuilder.buildProjectWithResult(
                projectPath.toString(),
                preparation.taskId()
        );
        if (buildResult != null) {
            return buildResult;
        }
        IllegalStateException contractViolation =
                new IllegalStateException("VueProjectBuilder returned a null build result");
        log.error("Vue 项目构建器违反非空结果契约，appId: {}, taskId: {}",
                appId, preparation.taskId(), contractViolation);
        throw new BusinessException(
                ErrorCode.SYSTEM_ERROR,
                "项目构建服务异常，请稍后重试",
                contractViolation
        );
    }

    private boolean validateRuntimeIfNeeded(Long appId,
                                            User loginUser,
                                            GenerationPreparation preparation,
                                            GenerationSession session,
                                            String stageMessage) {
        if (preparation.targetType() != CodeGenTypeEnum.VUE_PROJECT
                && preparation.targetType() != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return true;
        }
        markGenerationStage(appId, AppConstant.GENERATING_STAGE_BUILD, stageMessage, session);
        GenerationPerformanceMonitorService.SpanTimer span =
                generationPerformanceMonitorService.startSpan(preparation.taskId(), "dev_server_validation", GenerationSpanCategory.VALIDATION);
        DevServerValidationResult dsResult = devServerValidationService.validate(
                preparation.taskId(), appId, loginUser.getId(), preparation.targetType());
        if (dsResult.isPassed()) {
            span.success();
        } else {
            span.failed(dsResult.summary());
        }
        session.emit(GenerationStreamEvent.devServerValidation(dsResult.summary(), dsResult.toEventData()));
        if (session.isCancelled()) {
            return false;
        }
        if (!dsResult.isPassed()) {
            log.warn("Dev Server 运行时验证失败，appId: {}, summary: {}", appId, dsResult.summary());
            return false;
        }
        return true;
    }

    private void emitBuildResult(GenerationSession session,
                                 GenerationPreparation preparation,
                                 VueBuildResult buildResult,
                                 Map<String, Object> extraData) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        String publicReport = buildResult.toPublicDiagnosticReport();
        if (extraData != null) {
            data.putAll(extraData);
        }
        data.put("success", buildResult.success());
        data.put("stage", buildResult.stage());
        data.put("projectPath", buildResult.publicProjectPath());
        data.put("summary", buildResult.publicSummary());
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
