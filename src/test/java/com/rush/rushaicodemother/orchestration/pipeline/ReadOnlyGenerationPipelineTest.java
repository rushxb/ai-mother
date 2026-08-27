package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionPolicy;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity;
import com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisResult;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisOutcome;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyEvidenceBasis;
import com.rush.rushaicodemother.orchestration.readonly.ReadOnlyAnalysisService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskFenceGuard;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReadOnlyGenerationPipelineTest {

    @Test
    void successfulAnalysisMustProduceCitedArtifactsWithoutWorkspaceEffects() {
        ReadOnlyAnalysisService analysisService = mock(ReadOnlyAnalysisService.class);
        GenerationPipelineRequest request = request("read-only-task");
        when(analysisService.analyze(
                eq("read-only-task"), eq(IntentOperationType.AUDIT),
                eq("审计鉴权链路，不要修改代码"), eq(request.workspace()),
                eq(CodeGenTypeEnum.VUE_PROJECT)))
                .thenReturn(completed(new ReadOnlyAnalysisResult(
                        "鉴权详情读取缺少所有权校验",
                        List.of(new ReadOnlyAnalysisResult.Finding(
                                "越权风险", "HIGH", "详情接口允许读取其他用户资源")),
                        List.of(new ReadOnlyAnalysisResult.FileReference(
                                "src/auth.ts", 18, "详情读取入口")),
                        "用户只要求审计，本次未修改工作区")));
        ReadOnlyGenerationPipeline pipeline = new ReadOnlyGenerationPipeline(
                mock(GenerationPerformanceMonitorService.class), analysisService);

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationTaskStatus.SUCCESS, outcome.terminalStatus());
        assertEquals(0, outcome.changedFileCount());
        assertEquals(0, outcome.repairRounds());
        assertTrue(outcome.completionEvidence().contains(GenerationCompletionEvidenceType.INTENT_COVERAGE));
        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.NO_CHANGE_JUSTIFICATION));
        assertTrue(outcome.completionEvidence().contains(GenerationCompletionEvidenceType.FAST_VALIDATION));
        assertNotNull(request.requireExecution().session().preparation().artifact("analysis"));
        assertNotNull(request.requireExecution().session().preparation()
                .artifact("no_change_justification"));
        assertEquals(false, request.requireExecution().session().preparation()
                .artifact("analysis").payload().get("workspacePublished"));
        assertFalse(outcome.resultSummary().isBlank());
        assertDoesNotThrow(() -> new GenerationCompletionPolicy(mock(GenerationTaskFenceGuard.class))
                .requireCompletable(
                        request.requireExecution().session(),
                        GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.FAST),
                        outcome.completionEvidence()));
    }

    @Test
    void emptyAnalysisMustNotBeReportedAsSuccessfulIntentCoverage() {
        ReadOnlyAnalysisService analysisService = mock(ReadOnlyAnalysisService.class);
        GenerationPipelineRequest request = request("read-only-empty-analysis");
        when(analysisService.analyze(
                eq("read-only-empty-analysis"), eq(IntentOperationType.AUDIT),
                eq("审计鉴权链路，不要修改代码"), eq(request.workspace()),
                eq(CodeGenTypeEnum.VUE_PROJECT)))
                .thenReturn(completed(new ReadOnlyAnalysisResult(
                        null, List.of(), List.of(), null)));
        ReadOnlyGenerationPipeline pipeline = new ReadOnlyGenerationPipeline(
                mock(GenerationPerformanceMonitorService.class), analysisService);

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("read_only_analysis_failed", outcome.reason());
        assertFalse(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.INTENT_COVERAGE));
    }

    private GenerationPipelineRequest request(String taskId) {
        App app = App.builder().id(1L).userId(2L)
                .codeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue()).build();
        User user = User.builder().id(2L).build();
        Path root = Path.of("target/read-only-pipeline-test").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, true,
                root, root, Set.of(), Set.of());
        IntentProfile profile = new IntentProfile(
                IntentOperationType.AUDIT,
                Set.of(IntentAffectedScope.AUTHENTICATION),
                IntentSemanticComplexity.MEDIUM,
                true,
                false,
                IntentDestructiveRisk.LOW,
                3,
                IntentValidationRisk.LOW,
                0.95);
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.READ_ONLY, 0.95, "只读审计",
                FallbackPolicy.NONE, ExpectedValidationLevel.FAST);
        GenerationExecutionContext context = new GenerationExecutionContext(
                taskId, 1L, 2L, Instant.now(),
                new GenerationRuntimeProperties().toLimits(), Clock.systemUTC());
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 1L);
        context.bindExecutionFence(fence);
        GenerationSession session = new GenerationSession(null, context);
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "审计鉴权链路，不要修改代码", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace,
                profile,
                decision,
                new GenerationTaskExecution(taskId, session, context, fence, Instant.now()));
    }

    private ReadOnlyAnalysisOutcome completed(ReadOnlyAnalysisResult result) {
        return ReadOnlyAnalysisOutcome.completed(
                result,
                ReadOnlyEvidenceBasis.REPOSITORY_FACTS,
                new ProtectedRepositoryContextEnvelope(
                        "protected",
                        "workspace-version",
                        List.of(),
                        1_000,
                        3,
                        9,
                        false,
                        false,
                        ProtectedRepositoryContextEnvelope.PromptInjectionRisk.NONE,
                        true
                )
        );
    }
}
