package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionPolicy;
import com.rush.rushaicodemother.orchestration.attempt.completion.HeavyGenerationCompletionEvidenceFactory;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationCommitResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import com.rush.rushaicodemother.orchestration.artifact.RollbackPoint;
import com.rush.rushaicodemother.orchestration.patch.GenerationPatchResultService;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.review.OrphanFileReviewService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationCommitService;
import com.rush.rushaicodemother.orchestration.snapshot.GenerationDiffSummaryService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 重型生成Finalization服务实现。
 */
@Service
public class HeavyGenerationFinalizationService {

    private final GenerationCommitService generationCommitService;
    private final GenerationDiffSummaryService generationDiffSummaryService;
    private final GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector;
    private final GenerationPatchResultService generationPatchResultService;
    private final OrphanFileReviewService orphanFileReviewService;
    private final GenerationWorkspaceService generationWorkspaceService;
    private final GenerationCompletionPolicy generationCompletionPolicy;

    /** 完成门禁必须参与正式收尾链路，因此栅栏校验是构造期强依赖。 */
    public HeavyGenerationFinalizationService(
            GenerationCommitService generationCommitService,
            GenerationDiffSummaryService generationDiffSummaryService,
            GenerationOrchestrationMetricsCollector generationOrchestrationMetricsCollector,
            GenerationPatchResultService generationPatchResultService,
            OrphanFileReviewService orphanFileReviewService,
            GenerationWorkspaceService generationWorkspaceService,
            GenerationCompletionPolicy generationCompletionPolicy
    ) {
        this.generationCommitService = generationCommitService;
        this.generationDiffSummaryService = generationDiffSummaryService;
        this.generationOrchestrationMetricsCollector = generationOrchestrationMetricsCollector;
        this.generationPatchResultService = generationPatchResultService;
        this.orphanFileReviewService = orphanFileReviewService;
        this.generationWorkspaceService = generationWorkspaceService;
        this.generationCompletionPolicy = generationCompletionPolicy;
    }

    /**
     * 在提交和发布工作区前校验结构化完成证据，证据不足时失败关闭。
     */
    public void requireCompletionEvidence(
            GenerationPreparation preparation,
            GenerationSession session
    ) {
        GenerationExecutionPlan executionPlan = session.executionPlan();
        GenerationExecutionPlan.ValidationGraph validationGraph = executionPlan != null
                ? executionPlan.validationGraph()
                : GenerationExecutionPlan.ValidationGraph.forLevel(
                        preparation.requiresBuildValidation()
                                ? ExpectedValidationLevel.BUILD
                                : ExpectedValidationLevel.FAST
                );
        generationCompletionPolicy.requireCompletable(
                session,
                validationGraph,
                HeavyGenerationCompletionEvidenceFactory.collect(preparation, session)
        );
    }

    /**
 * 发送{@code Diff}汇总{@code If}可用事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 */
    public void emitDiffSummaryIfAvailable(Long appId,
                                           GenerationPreparation preparation,
                                           GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationArtifact rollbackPoint = preparation.artifact(RollbackPoint.KEY);
        DiffSummary summary = session.executionWorkspace() != null
                ? generationDiffSummaryService.summarize(
                        appId,
                        preparation.targetType(),
                        preparation.taskId(),
                        rollbackPoint,
                        session.executionWorkspace().workspace())
                : generationDiffSummaryService.summarize(
                        appId,
                        preparation.targetType(),
                        preparation.taskId(),
                        rollbackPoint);
        GenerationArtifact diffSummary = summary.toArtifact();
        preparation.putArtifact(diffSummary);
        session.emit(GenerationStreamEvent.agentEvent(
                generationDiffSummaryService.renderText(summary),
                buildDiffSummaryEventData(preparation, diffSummary)
        ));
        emitPatchResultIfAvailable(appId, preparation, session, diffSummary);
    }

    /**
 * 发送提交结果{@code If}可用事件。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param session 会话
 */
    public void emitCommitResultIfAvailable(Long appId,
                                            GenerationPreparation preparation,
                                            GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        GenerationCommitResult commitResult = generationCommitService.commit(
                appId,
                preparation.taskId(),
                preparation.artifact(DiffSummary.KEY)
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

    /**
 * 构建并返回{@code Diff}汇总事件{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param diffSummary {@code diffSummary} 对应的调用参数
 * @return {@code Diff}汇总事件{@code Data}集合
 */
    public Map<String, Object> buildDiffSummaryEventData(GenerationPreparation preparation,
                                                         GenerationArtifact diffSummary) {
        DiffSummary summary = DiffSummary.fromArtifact(
                diffSummary,
                null,
                preparation.taskId()
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "diff");
        data.put("status", summary.status());
        data.put("summary", summary.created()
                ? "生成后差异摘要已生成"
                : "生成后差异摘要已跳过");
        data.put("taskId", preparation.taskId());
        // 校验与状态解释走强类型模型，事件仍透传原制品载荷，保持生命周期对象身份稳定。
        data.put("artifact", diffSummary.payload());
        return data;
    }

    /**
 * 构建并返回补丁结果事件{@code Data}。
 *
 * @param appId 应用编号
 * @param preparation {@code preparation} 对应的调用参数
 * @param patchResult 补丁结果
 * @return 补丁结果事件{@code Data}集合
 */
    public Map<String, Object> buildPatchResultEventData(Long appId,
                                                         GenerationPreparation preparation,
                                                         GenerationArtifact patchResult) {
        PatchResult result = PatchResult.fromArtifact(
                patchResult,
                appId,
                preparation.taskId()
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("agent", "Orchestrator");
        data.put("stage", "patch");
        data.put("status", result.status());
        data.put("summary", result.applied()
                ? "Patch 实际落盘结果已对齐"
                : "Patch 实际落盘结果存在偏差或已跳过");
        data.put("taskId", preparation.taskId());
        data.put("artifact", patchResult.payload());
        return data;
    }

    /**
 * 构建并返回{@code Orphan}{@code Review}事件{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param orphanReview {@code orphanReview} 对应的调用参数
 * @return {@code Orphan}{@code Review}事件{@code Data}集合
 */
    public Map<String, Object> buildOrphanReviewEventData(GenerationPreparation preparation,
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

    /**
 * 构建并提交并返回结果事件{@code Data}。
 *
 * @param preparation {@code preparation} 对应的调用参数
 * @param commitResult 提交结果
 * @return 提交结果事件{@code Data}集合
 */
    public Map<String, Object> buildCommitResultEventData(GenerationPreparation preparation,
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

    /** 发送补丁结果{@code If}可用事件。 */
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
        GenerationArtifact patchResultArtifact = patchResult.toArtifact();
        preparation.putArtifact(patchResultArtifact);
        generationOrchestrationMetricsCollector.recordPatchResult(
                "agent",
                patchResult.status(),
                patchResult.reason()
        );
        session.emit(GenerationStreamEvent.agentEvent(
                generationPatchResultService.renderText(patchResult),
                buildPatchResultEventData(appId, preparation, patchResultArtifact)
        ));
        emitOrphanFileReviewIfAvailable(appId, preparation, session);
    }

    /** 发送{@code Orphan}文件{@code Review}{@code If}可用事件。 */
    private void emitOrphanFileReviewIfAvailable(Long appId,
                                                 GenerationPreparation preparation,
                                                 GenerationSession session) {
        if (session.isCancelled()) {
            return;
        }
        Path projectRoot = session.executionWorkspace() != null
                && session.executionWorkspace().appId().equals(appId)
                && session.executionWorkspace().codeGenType() == preparation.targetType()
                ? session.executionWorkspace().workspace().canonicalRootPath()
                : generationWorkspaceService.resolve(appId, preparation.targetType()).canonicalRootPath();
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
}
