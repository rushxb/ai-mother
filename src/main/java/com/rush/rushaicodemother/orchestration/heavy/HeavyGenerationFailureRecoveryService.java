package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.service.impl.GeneratedProjectWorkspaceInspector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeavyGenerationFailureRecoveryService {

    private final GenerationAppStateService generationAppStateService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationRollbackRestoreService generationRollbackRestoreService;

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

    public void emitBuildFailure(Long appId,
                                 GenerationPreparation preparation,
                                 GenerationSession session,
                                 String failureSummary) {
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
        GenerationErrorClassifier.GenerationError generationError = classifyGenerationError(failureSummary);
        session.emit(GenerationStreamEvent.generationError(
                failureSummary,
                buildGenerationErrorData(preparation, generationError, failureSummary)
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
        cleanupCodeDir(appId, preparation.targetType());
        generationAppStateService.switchAppCodeGenType(appId, preparation.originalType());
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
}
