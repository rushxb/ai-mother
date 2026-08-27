package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.ChatHistoryMessageTypeEnum;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.event.GenerationEventPublisher;
import com.rush.rushaicodemother.orchestration.event.GenerationEventType;
import com.rush.rushaicodemother.orchestration.index.WorkspaceSemanticIndexService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentEditGenerationService {

    private final AgentEditContextCollector contextCollector;
    private final AgentEditUnderstandingService understandingService;
    private final AgentEditPlanningService planningService;
    private final AgentEditPatchService patchService;
    private final AgentEditVerificationService verificationService;
    private final AgentEditRepairService repairService;
    private final GenerationEditModelInvoker editModelInvoker;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationEventPublisher generationEventPublisher;
    private final GenerationTaskLifecycleService lifecycleService;
    private final ChatHistoryService chatHistoryService;
    private final WorkspaceSemanticIndexService workspaceSemanticIndexService;
    private final EditFileSnapshotService editFileSnapshotService;
    private final EditStatePersistenceService editStatePersistenceService;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final AgentEditRepositoryContextAssembler repositoryContextAssembler;

    /** 为统一任务运行时之外的隔离调用者保留旧入口点。 */
    @Deprecated(forRemoval = false)
    public AgentEditResult execute(GenerationTaskRequest request, GenerationModeDecision modeDecision) {
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        return executeInternal(
                generateTaskId(), request, modeDecision,
                generationWorkspaceService.resolve(app, codeGenType), false, null);
    }

    /** 使用提交运行时分配的任务标识执行 AGENT_EDIT。 */
    public AgentEditResult execute(String taskId,
                                   GenerationTaskRequest request,
                                   GenerationModeDecision modeDecision) {
        App app = request.app();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        return execute(
                taskId,
                request,
                modeDecision,
                generationWorkspaceService.resolve(app, codeGenType)
        );
    }

    /** 针对持久工作进程纪元选择的确切工作空间执行 AGENT_EDIT。 */
    public AgentEditResult execute(String taskId,
                                   GenerationTaskRequest request,
                                   GenerationModeDecision modeDecision,
                                   GenerationWorkspace workspace) {
        return execute(taskId, request, modeDecision, workspace, null);
    }

    /** 针对持久工作进程冻结的执行计划运行 AGENT_EDIT。 */
    public AgentEditResult execute(String taskId,
                                   GenerationTaskRequest request,
                                   GenerationModeDecision modeDecision,
                                   GenerationWorkspace workspace,
                                   GenerationExecutionPlan executionPlan) {
        return executeInternal(taskId, request, modeDecision, workspace, true, executionPlan);
    }

    /** 执行内部处理流程。 */
    private AgentEditResult executeInternal(String taskId,
                                            GenerationTaskRequest request,
                                            GenerationModeDecision modeDecision,
                                            GenerationWorkspace workspace,
                                            boolean managedModelCalls,
                                            GenerationExecutionPlan executionPlan) {
        requireTaskId(taskId);
        App app = request.app();
        User loginUser = request.loginUser();
        String userMessage = request.message();
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        requireWorkspace(app, codeGenType, workspace);
        GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.resolve(
                executionPlan,
                modeDecision == null ? null : modeDecision.expectedValidationLevel()
        );
        Instant startedAt = Instant.now();

        performanceMonitorService.startTask(
                taskId,
                app.getId(),
                loginUser.getId(),
                GenerationRoute.AGENT_EDIT,
                codeGenType.getValue(),
                startedAt,
                modeDecision
        );
        lifecycleService.recordUserMessage(app, loginUser, userMessage);
        lifecycleService.startGeneration(
                taskId, app, loginUser, codeGenType, codeGenType,
                userMessage, userMessage, true, "agent_edit",
                GenerationRoute.AGENT_EDIT, AppConstant.GENERATING_STAGE_UPDATE);

        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            AgentEditReadResult readResult = read(request, workspace, userMessage, codeGenType, taskId);
            if (readResult.isEmpty()) {
                return fail(request, app, loginUser, taskId, "AGENT_EDIT 未定位到可编辑上下文", 0, null);
            }
            AgentEditUnderstanding understanding = understand(request, taskId, readResult);

            ProtectedRepositoryContextEnvelope contextEnvelope = repositoryContextAssembler.assemble(
                    readResult, understanding, userMessage);
            String projectContext = contextEnvelope.content();
            EditResult editResult = editWithAi(
                    taskId, userMessage, projectContext, managedModelCalls);
            List<PatchOperation> patchOperations = planningService.convertToPatchOperations(editResult);
            if (patchOperations.isEmpty()) {
                return fail(request, app, loginUser, taskId, "AGENT_EDIT 未生成有效补丁操作", 0, null);
            }
            EditChangePlan changePlan = plan(request, taskId, readResult, understanding, codeGenType, editResult, patchOperations);
            Path projectRoot = workspace.canonicalRootPath();
            try (EditWorkspaceTransaction workspaceTransaction = editFileSnapshotService.beginTransaction(
                    taskId, projectRoot, patchOperations)) {
                ApplyAndVerifyOutcome outcome = applyAndVerify(
                        request, app, loginUser, taskId, workspace, projectRoot,
                        changePlan, patchOperations, userMessage, verificationPolicy, 0
                );
                int repairRounds = 0;
                if (!outcome.success()) {
                    AgentEditRepairService.RepairAttempt repairAttempt = managedModelCalls
                            ? repairService.repair(
                            taskId, userMessage, projectContext,
                            outcome.validationResult(), outcome.applyResult())
                            : repairService.repair(
                            userMessage, projectContext,
                            outcome.validationResult(), outcome.applyResult());
                    if (!repairAttempt.patchOperations().isEmpty()) {
                        repairRounds = 1;
                        workspaceTransaction.include(repairAttempt.patchOperations());
                        EditChangePlan repairPlan = plan(
                                request, taskId, readResult, understanding, codeGenType,
                                repairAttempt.editResult(), repairAttempt.patchOperations()
                        );
                        outcome = applyAndVerify(
                                request, app, loginUser, taskId, workspace, projectRoot,
                                repairPlan, repairAttempt.patchOperations(), userMessage,
                                verificationPolicy, repairRounds
                        );
                        patchOperations = repairAttempt.patchOperations();
                        editResult = repairAttempt.editResult();
                        changePlan = repairPlan;
                    }
                }
                performanceMonitorService.recordRuntimeTelemetry(taskId, Map.of(
                        "modelName", "routing_chat_model",
                        "toolCallCount", 0,
                        "toolDurationMs", 0,
                        "repairRounds", repairRounds
                ));
                if (!outcome.success()) {
                    EditFileSnapshotService.RestoreResult restoreResult = workspaceTransaction.rollback();
                    generationEventPublisher.publish(request, GenerationEventType.EDIT_ROLLBACK,
                            "AGENT_EDIT 验证未通过，已回滚本次编辑", Map.of(
                                    "taskId", taskId,
                                    "status", restoreResult.status(),
                                    "restoredFiles", restoreResult.restoredFiles(),
                                    "failedFiles", restoreResult.failedFiles()
                            ));
                    return fail(request, app, loginUser, taskId,
                            buildFailureSummary(outcome), repairRounds, restoreResult);
                }

                List<String> changedFiles = patchOperations.stream().map(PatchOperation::relativePath).toList();
                workspaceSemanticIndexService.refreshFilesIndex(projectRoot, changedFiles);
                editStatePersistenceService.recordEditResult(app.getId(), taskId, patchOperations, true);
                String summary = buildSuccessSummary(editResult, changePlan, outcome);
                chatHistoryService.addChatMessage(
                        app.getId(), summary, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
                workspaceTransaction.commit();
                return new AgentEditResult(
                        taskId,
                        GenerationRoute.AGENT_EDIT,
                        summary,
                        changedFiles,
                        "success",
                        repairRounds,
                        outcome.completionEvidence());
            }
        } catch (GenerationExecutionPolicyException executionPolicyFailure) {
            throw executionPolicyFailure;
        } catch (Exception e) {
            log.error("AGENT_EDIT 执行失败，appId: {}, taskId: {}", app.getId(), taskId, LogExceptionSanitizer.sanitize(e));
            GenerationErrorClassifier.GenerationError publicError = GenerationErrorClassifier.classify(e);
            return fail(request, app, loginUser, taskId,
                    "AGENT_EDIT 执行失败: " + publicError.message(),
                    0,
                    null
            );
        }
    }

    /** 读取智能体编辑生成。 */
    private AgentEditReadResult read(GenerationTaskRequest request,
                                     GenerationWorkspace workspace,
                                     String userMessage,
                                     CodeGenTypeEnum codeGenType,
                                     String taskId) {
        Instant startedAt = Instant.now();
        AgentEditReadResult readResult = contextCollector.collect(workspace, userMessage, codeGenType);
        generationEventPublisher.publish(request, GenerationEventType.AGENT_EDIT_READ, "AGENT_EDIT Read 阶段完成", Map.of(
                "taskId", taskId,
                "intent", readResult.intent(),
                "candidateCount", readResult.candidateFiles().size(),
                "selectedFiles", readResult.selectedFiles(),
                "importRelations", readResult.importRelations(),
                "referencedBy", readResult.referencedBy(),
                "symbols", readResult.symbols(),
                "riskLevel", readResult.riskLevel()
        ));
        performanceMonitorService.recordSpan(
                taskId,
                "agent_edit_read",
                GenerationSpanCategory.PIPELINE,
                "success",
                Duration.between(startedAt, Instant.now()),
                String.valueOf(readResult.selectedFiles())
        );
        return readResult;
    }

    /** 返回{@code understand}。 */
    private AgentEditUnderstanding understand(GenerationTaskRequest request, String taskId, AgentEditReadResult readResult) {
        Instant startedAt = Instant.now();
        AgentEditUnderstanding understanding = understandingService.understand(readResult);
        generationEventPublisher.publish(request, GenerationEventType.AGENT_EDIT_UNDERSTAND, "AGENT_EDIT Understand 阶段完成", Map.of(
                "taskId", taskId,
                "summary", understanding.structureSummary(),
                "affectedFiles", understanding.affectedFiles(),
                "protectedFiles", understanding.protectedFiles(),
                "referencedBy", understanding.referencedBy(),
                "symbols", understanding.symbols(),
                "diagnostics", understanding.diagnostics(),
                "riskLevel", understanding.riskLevel()
        ));
        performanceMonitorService.recordSpan(
                taskId,
                "agent_edit_understand",
                GenerationSpanCategory.PIPELINE,
                "success",
                Duration.between(startedAt, Instant.now()),
                understanding.riskLevel()
        );
        return understanding;
    }

    /** 返回计划。 */
    private EditChangePlan plan(GenerationTaskRequest request,
                                String taskId,
                                AgentEditReadResult readResult,
                                AgentEditUnderstanding understanding,
                                CodeGenTypeEnum codeGenType,
                                EditResult editResult,
                                List<PatchOperation> patchOperations) {
        Instant startedAt = Instant.now();
        EditChangePlan changePlan = planningService.plan(readResult, understanding, codeGenType, editResult, patchOperations);
        generationEventPublisher.publish(request, GenerationEventType.AGENT_EDIT_PLAN, "AGENT_EDIT Plan 阶段完成", Map.of(
                "taskId", taskId,
                "scope", changePlan.scope(),
                "modifyFiles", changePlan.modifyFiles(),
                "addFiles", changePlan.addFiles(),
                "deleteFiles", changePlan.deleteFiles(),
                "validation", changePlan.validation(),
                "rollback", changePlan.rollback()
        ));
        performanceMonitorService.recordSpan(
                taskId,
                "agent_edit_plan",
                GenerationSpanCategory.PIPELINE,
                "success",
                Duration.between(startedAt, Instant.now()),
                changePlan.scope()
        );
        return changePlan;
    }

    /** 返回编辑并AI。 */
    private EditResult editWithAi(String taskId,
                                  String userMessage,
                                  String projectContext,
                                  boolean managedModelCall) {
        Instant startedAt = Instant.now();
            String agentEditMessage = """
                %s

                请按 Claude Code 式编辑流程执行：先理解上下文，再只输出必要的结构化 JSON 编辑操作。
                必须遵守：
                1. 只修改计划内相关文件，不要重写整个项目。
                2. 优先 replace 小片段；只有确实需要时才 modify 整文件。
                3. 不要新增未声明依赖。
                4. 删除文件必须是用户明确要求或修复必需。
                """.formatted(StrUtil.blankToDefault(userMessage, ""));
        try {
            return managedModelCall
                    ? editModelInvoker.invokeManaged(taskId, "initial", agentEditMessage, projectContext)
                    : editModelInvoker.invokeLegacy(agentEditMessage, projectContext);
        } finally {
            performanceMonitorService.recordRuntimeTelemetry(taskId, Map.of(
                    "modelName", "routing_chat_model",
                    "totalAiDurationMs", Duration.between(startedAt, Instant.now()).toMillis()
            ));
        }
    }

    /** 应用{@code And}{@code Verify}。 */
    private ApplyAndVerifyOutcome applyAndVerify(GenerationTaskRequest request,
                                                 App app,
                                                 User loginUser,
                                                 String taskId,
                                                 GenerationWorkspace workspace,
                                                 Path projectRoot,
                                                 EditChangePlan changePlan,
                                                 List<PatchOperation> patchOperations,
                                                 String userMessage,
                                                 GenerationVerificationPolicy verificationPolicy,
                                                 int repairRound) {
        Instant editStartedAt = Instant.now();
        PatchApplyResult applyResult = patchService.apply(app.getId(), taskId, projectRoot, changePlan, patchOperations);
        generationEventPublisher.publish(request, GenerationEventType.PATCH_APPLY, "AGENT_EDIT Edit 阶段补丁应用完成", Map.of(
                "taskId", taskId,
                "round", repairRound,
                "status", applyResult.status(),
                "appliedCount", applyResult.appliedOperationCount(),
                "reason", StrUtil.blankToDefault(applyResult.reason(), ""),
                "rejectedOperations", applyResult.rejectedOperations()
        ));
        performanceMonitorService.recordSpan(
                taskId,
                "agent_edit_edit",
                applyResult.status(),
                Duration.between(editStartedAt, Instant.now()),
                StrUtil.blankToDefault(applyResult.reason(), "")
        );
        if (!"applied".equals(applyResult.status())) {
            return new ApplyAndVerifyOutcome(
                    false, applyResult, null, GenerationCompletionEvidenceSet.empty());
        }

        Instant verifyStartedAt = Instant.now();
        AgentEditVerificationOutcome verificationOutcome = verificationService.verify(
                taskId,
                app.getId(),
                loginUser,
                workspace,
                patchOperations,
                changePlan,
                userMessage,
                verificationPolicy
        );
        BackgroundValidationService.ValidationResult validationResult =
                verificationOutcome == null ? null : verificationOutcome.validationResult();
        boolean valid = verificationOutcome != null && verificationOutcome.success();
        generationEventPublisher.publish(request, GenerationEventType.AGENT_EDIT_VERIFY, "AGENT_EDIT Verify 阶段完成", Map.of(
                "taskId", taskId,
                "round", repairRound,
                "status", validationResult == null ? "unknown" : validationResult.status(),
                "message", validationResult == null ? "" : validationResult.message()
        ));
        performanceMonitorService.recordSpan(
                taskId,
                "agent_edit_verify",
                GenerationSpanCategory.VALIDATION,
                valid ? "success" : "failed",
                Duration.between(verifyStartedAt, Instant.now()),
                validationResult == null ? "" : validationResult.message()
        );
        return new ApplyAndVerifyOutcome(
                valid,
                applyResult,
                validationResult,
                verificationOutcome == null
                        ? GenerationCompletionEvidenceSet.empty()
                        : verificationOutcome.completionEvidence());
    }

    private AgentEditResult fail(GenerationTaskRequest request,
                                 App app,
                                 User loginUser,
                                 String taskId,
                                 String reason,
                                 int repairRounds,
                                 EditFileSnapshotService.RestoreResult restoreResult) {
        generationEventPublisher.publish(request, GenerationEventType.TASK_FAILED, "AGENT_EDIT 失败", Map.of(
                "taskId", taskId,
                "reason", StrUtil.blankToDefault(reason, ""),
                "repairRounds", repairRounds,
                "rollbackStatus", restoreResult == null ? "" : restoreResult.status()
        ));
        chatHistoryService.addChatMessage(app.getId(), reason, ChatHistoryMessageTypeEnum.AI.getValue(), loginUser.getId());
        performanceMonitorService.finishTask(taskId, "failed");
        return new AgentEditResult(taskId, GenerationRoute.AGENT_EDIT, reason, List.of(), "failed", repairRounds);
    }

    private String buildSuccessSummary(EditResult editResult, EditChangePlan changePlan, ApplyAndVerifyOutcome outcome) {
        String summary = editResult == null ? "" : StrUtil.blankToDefault(editResult.summary(), "");
        if (StrUtil.isBlank(summary)) {
            summary = "AGENT_EDIT 已完成代码修改";
        }
        return summary
                + "\n\nChangePlan: " + changePlan.scope()
                + "，验证: " + (outcome.validationResult() == null ? "未执行" : outcome.validationResult().status());
    }

    /** 构建并返回失败汇总。 */
    private String buildFailureSummary(ApplyAndVerifyOutcome outcome) {
        if (outcome == null) {
            return "AGENT_EDIT 失败";
        }
        if (outcome.validationResult() != null) {
            return "AGENT_EDIT 验证失败，已回滚本次编辑: " + outcome.validationResult().message();
        }
        if (outcome.applyResult() != null) {
            return "AGENT_EDIT 补丁应用失败，已回滚本次编辑: "
                    + StrUtil.blankToDefault(outcome.applyResult().reason(), outcome.applyResult().status());
        }
        return "AGENT_EDIT 失败，已回滚本次编辑";
    }

    private void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
    }

    private void requireWorkspace(App app,
                                  CodeGenTypeEnum codeGenType,
                                  GenerationWorkspace workspace) {
        if (app == null || app.getId() == null || codeGenType == null || workspace == null
                || !app.getId().equals(workspace.appId()) || workspace.codeGenType() != codeGenType) {
            throw new IllegalArgumentException("agent edit workspace identity mismatch");
        }
    }

    private String generateTaskId() {
        return "agent_edit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private record ApplyAndVerifyOutcome(
            boolean success,
            PatchApplyResult applyResult,
            BackgroundValidationService.ValidationResult validationResult,
            GenerationCompletionEvidenceSet completionEvidence
    ) {
        private ApplyAndVerifyOutcome {
            completionEvidence = completionEvidence == null
                    ? GenerationCompletionEvidenceSet.empty()
                    : completionEvidence;
        }
    }
}
