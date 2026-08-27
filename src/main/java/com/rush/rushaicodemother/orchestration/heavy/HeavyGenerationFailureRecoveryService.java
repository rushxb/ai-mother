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
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.artifact.RollbackRestore;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationRollbackRestoreService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GeneratedProjectWorkspaceInspection;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
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

    /** 针对遗留形状编译的重点测试和调用者的兼容性构造函数。 */
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

    /**
 * 发送生成错误事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 * @param throwable 待处理的异常
 */
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


    /**
 * 发送执行策略错误事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 * @param exception 待转换或处理的异常
 */
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

    /**
 * 发送构建失败事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 * @param failureSummary 失败汇总
 */
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

    /**
 * 发送{@code Missing}项目代码事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 * @param workspaceState 工作区状态
 */
    public void emitMissingProjectCode(Long appId,
                                       GenerationPreparation preparation,
                                       GenerationSession session,
                                       GeneratedProjectWorkspaceInspection workspaceState) {
        emitRollbackRestoreIfAllowed(appId, preparation, session);
        emitMissingProjectCodeError(appId, preparation, session, workspaceState);
        rollbackCodeGenTypeIfNeeded(appId, preparation);
    }

    /**
 * 发送回滚恢复{@code If}{@code Allowed}事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 */
    public void emitRollbackRestoreIfAllowed(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationArtifact existingRestore = preparation.artifact(RollbackRestore.KEY);
        if (existingRestore != null) {
            try {
                RollbackRestore parsed = RollbackRestore.fromArtifact(
                        existingRestore,
                        appId,
                        preparation.taskId()
                );
                if (parsed.trustedForReplay()) {
                    return;
                }
                log.warn("Ignoring legacy rollback restore artifact, appId: {}, taskId: {}",
                        appId, preparation.taskId());
                preparation.putArtifact(RollbackRestore.skipped(
                        appId,
                        preparation.taskId(),
                        "manual_retry_without_snapshot",
                        "",
                        "",
                        "rollback_restore_identity_unsupported"
                ).toArtifact());
            } catch (IllegalArgumentException invalidArtifact) {
                log.warn("Ignoring invalid rollback restore artifact, appId: {}, taskId: {}, error: {}",
                        appId,
                        preparation.taskId(),
                        invalidArtifact.getClass().getSimpleName());
                // 托管执行纪元不会覆盖已发布工作区；即使后续无需物理恢复，也不能继续
                // 把外来制品投影给用户。先写入当前任务的安全跳过结果，旧路径实际恢复后会覆盖它。
                preparation.putArtifact(RollbackRestore.skipped(
                        appId,
                        preparation.taskId(),
                        "manual_retry_without_snapshot",
                        "",
                        "",
                        "rollback_restore_artifact_invalid"
                ).toArtifact());
            }
        }
        // 孤立的执行纪元并未改变已发布的工作空间。恢复旧的
        // 仅通过 appId 进行快照就会重新引入 epoch 工作区所避免的 TOCTOU 竞赛。
        // 失败纪元由统一 Finalizer 移入带 TTL 的隔离目录，不在这里按 appId 操作文件。
        if (session.executionWorkspace() != null) {
            return;
        }
        GenerationArtifact rollbackRestore = generationRollbackRestoreService.restoreIfAllowed(
                appId,
                preparation.taskId(),
                preparation.artifact(ChangePlan.KEY),
                preparation.artifact(RollbackPoint.KEY)
        );
        RollbackRestore restore = RollbackRestore.fromArtifact(
                rollbackRestore,
                appId,
                preparation.taskId()
        );
        preparation.putArtifact(rollbackRestore);
        generationOrchestrationMetricsCollector.recordRollbackRestore(
                "agent",
                restore.status(),
                restore.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                buildRollbackRestoreMessage(restore),
                buildRollbackRestoreEventData(preparation, rollbackRestore)
        ));
    }

    /**
 * 处理回滚代码生成类型{@code If}{@code Needed}。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 */
    public void rollbackCodeGenTypeIfNeeded(Long appId, GenerationPreparation preparation) {
        if (preparation == null || !preparation.upgradeRequired()) {
            return;
        }
        generationAppStateService.updateOwnedCodeGenType(
                appId, preparation.taskId(), preparation.originalType());
        // 失败纪元仍是私有执行工作区，由统一 Finalizer 按完整 execution fence 隔离。
        // 此处禁止从 appId + type 重新解析并删除，避免租约接管后误伤较新的执行版本。
    }

    /**
 * 构建并返回生成错误{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param generationError 生成错误
 * @return 生成错误{@code Data}集合
 */
    public Map<String, Object> buildGenerationErrorData(GenerationPreparation preparation,
                                                        GenerationErrorClassifier.GenerationError generationError) {
        return buildGenerationErrorData(preparation, generationError, generationError.message());
    }

    /**
 * 构建并返回生成错误{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param generationError 生成错误
 * @param message 消息内容
 * @return 生成错误{@code Data}集合
 */
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

    /**
 * 构建并返回生成错误{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param category {@code category} 对应的调用参数
 * @param message 消息内容
 * @param recoverable {@code recoverable} 对应的调用参数
 * @param extraData {@code extraData} 对应的调用参数
 * @return 生成错误{@code Data}集合
 */
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
        putArtifactPayload(data, preparation, RollbackPoint.KEY);
        putArtifactPayload(data, preparation, "diff_summary");
        putArtifactPayload(data, preparation, "patch_result");
        putArtifactPayload(data, preparation, GenerationCommitResult.KEY);
        putArtifactPayload(data, preparation, RollbackRestore.KEY);
        return data;
    }

    /**
 * 构建并返回回滚恢复事件{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param rollbackRestore 回滚恢复
 * @return 回滚恢复事件{@code Data}集合
 */
    public Map<String, Object> buildRollbackRestoreEventData(GenerationPreparation preparation,
                                                             GenerationArtifact rollbackRestore) {
        RollbackRestore restore = RollbackRestore.fromArtifact(
                rollbackRestore,
                null,
                preparation.taskId()
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "rollback");
        data.put("status", restore.status());
        data.put("summary", buildRollbackRestoreMessage(restore));
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

    public String buildMissingProjectCodeMessage(GeneratedProjectWorkspaceInspection workspaceState) {
        return workspaceState.missingProjectSummary()
                + "。请重试生成；如果持续出现，请检查模型工具调用是否成功写入关键项目文件。";
    }

    /** 发送{@code Missing}项目代码错误事件。 */
    private void emitMissingProjectCodeError(Long appId,
                                             GenerationPreparation preparation,
                                             GenerationSession session,
                                             GeneratedProjectWorkspaceInspection workspaceState) {
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

    /** 构建并返回回滚恢复消息。 */
    private String buildRollbackRestoreMessage(RollbackRestore rollbackRestore) {
        if ("restored".equals(rollbackRestore.status())) {
            return "生成失败，已从本地回滚点恢复项目文件。";
        }
        if ("failed".equals(rollbackRestore.status())) {
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
