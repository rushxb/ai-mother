package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class HeavyGenerationFailureRecoveryService {

    private final GenerationAppStateService generationAppStateService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationRollbackRestoreService generationRollbackRestoreService;

    @Autowired
    public HeavyGenerationFailureRecoveryService(
            GenerationAppStateService generationAppStateService,
            GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector,
            GenerationRollbackRestoreService generationRollbackRestoreService
    ) {
        this.generationAppStateService = generationAppStateService;
        this.generationOrchestrationMetricsCollector = generationOrchestrationMetricsCollector;
        this.generationRollbackRestoreService = generationRollbackRestoreService;
    }

    /** Compatibility constructor for focused tests and callers compiled against the legacy shape. */
    public HeavyGenerationFailureRecoveryService(
            GenerationAppStateService generationAppStateService,
            GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector,
            GenerationRollbackRestoreService generationRollbackRestoreService,
            GenerationWorkspaceService ignoredWorkspaceService,
            GenerationTaskFenceGuard ignoredFenceGuard
    ) {
        this(generationAppStateService, generationOrchestrationMetricsCollector,
                generationRollbackRestoreService);
    }

    public void emitGenerationError(Long appId,
                                    GenerationPreparation preparation,
                                    GenerationSession session,
                                    Throwable throwable) {
        GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(throwable);
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        session.emit(GenerationStreamEvent.generationError(
                generationError.message(),
                buildGenerationErrorData(preparation, generationError)
        ));
    }


    public void emitExecutionPolicyError(Long appId,
                                         GenerationPreparation preparation,
                                         GenerationSession session,
                                         GenerationExecutionPolicyException exception) {
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        String category = "execution_policy";
        String message = "生成任务因执行策略限制已终止";
        Map<String, Object> details = new LinkedHashMap<>();
        if (exception instanceof GenerationDeadlineExceededException) {
            category = "task_deadline";
            message = "生成任务已超过总时限，请稍后重试";
        } else if (exception instanceof GenerationBudgetExceededException budgetExceededException) {
            category = "execution_budget";
            message = budgetExceededException.getMessage();
            details.put("budgetKind", budgetExceededException.budgetKind().name());
            details.put("budgetLimit", budgetExceededException.limit());
        }
        session.emit(GenerationStreamEvent.generationError(
                message,
                buildGenerationErrorData(preparation, category, message, false, details)
        ));
    }

    public void emitBuildFailure(Long appId,
                                 GenerationPreparation preparation,
                                 GenerationSession session,
                                 String failureSummary) {
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        String publicFailureSummary = PublicDiagnosticSanitizer.sanitizeSingleLine(failureSummary, 1_200);
        GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(publicFailureSummary);
        session.emit(GenerationStreamEvent.generationError(
                publicFailureSummary,
                buildGenerationErrorData(preparation, generationError, publicFailureSummary)
        ));
    }

    public void emitMissingProjectCode(Long appId,
                                       GenerationPreparation preparation,
                                       GenerationSession session,
                                       GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        emitMissingProjectCodeError(appId, preparation, session, workspaceState);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
    }

    public void emitRollbackRestoreIfAllowed(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session) {
        if (session.isCancelled() || preparation.artifact("rollback_restore") != null) {
            return;
        }
        // An isolated execution epoch has not mutated the published workspace. Restoring an old
        // snapshot by appId alone would reintroduce the very TOCTOU race the epoch workspace avoids.
        // The failed epoch is retained for diagnostics and later janitor reclamation instead.
        if (session.executionWorkspace() != null) {
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

    public void rollbackCodeGenTypeIfNeeded(Long appId, GenerationPreparation preparation) {
        if (preparation == null || !preparation.upgradeRequired()) {
            return;
        }
        generationAppStateService.updateOwnedCodeGenType(
                appId, preparation.taskId(), preparation.originalType());
        // Failed epochs remain private execution workspaces and are reclaimed by the janitor.
        // Deleting a path re-resolved from appId + type could remove a newer published version
        // after lease takeover, so failure recovery deliberately performs no direct filesystem delete.
    }

    public Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                        GenerationErrorClassifier.GenerationError generationError) {
        return buildGenerationErrorData(preparation, generationError, generationError.message());
    }

    public Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
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

    public Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
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
        putArtifactPayload(data, preparation, "rollback_point");
        putArtifactPayload(data, preparation, "diff_summary");
        putArtifactPayload(data, preparation, "patch_result");
        putArtifactPayload(data, preparation, "generation_commit");
        putArtifactPayload(data, preparation, "rollback_restore");
        return data;
    }

    public Map<String, Object> buildRollbackRestoreEventData(GenerationPreparation preparation,
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

    public GenerationErrorClassifier.GenerationError classifyGenerationError(Throwable throwable) {
        return GenerationErrorClassifier.classify(throwable);
    }

    public GenerationErrorClassifier.GenerationError classifyGenerationError(String errorMessage) {
        return GenerationErrorClassifier.classify(errorMessage);
    }

    public String buildMissingProjectCodeMessage(GeneratedProjectWorkspaceInspector.WorkspaceState workspaceState) {
        return workspaceState.missingProjectSummary()
                + "。请重试生成；如果持续出现，请检查模型工具调用是否成功写入关键项目文件。";
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

    private void putArtifactPayload(Map<String, Object> data,
                                    GenerationPreparation preparation,
                                    String artifactKey) {
        GenerationArtifact artifact = preparation.artifacts() == null ? null : preparation.artifacts().get(artifactKey);
        if (artifact != null) {
            data.put(artifactKey, artifact.payload());
        }
    }

}
