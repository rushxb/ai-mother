package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceType;
import com.rush.rushaicodemother.orchestration.attempt.completion.ObservedValidationCompletionEvidenceFactory;
import com.rush.rushaicodemother.orchestration.edit.AgentEditGenerationService;
import com.rush.rushaicodemother.orchestration.edit.AgentEditResult;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEditGenerationPipelineTest {

    @Test
    void failedAgentEditMustTerminateSameTaskInsteadOfAllocatingFallbackIdentity() {
        AgentEditGenerationService service = mock(AgentEditGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        AgentEditGenerationPipeline pipeline = new AgentEditGenerationPipeline(service, monitor);
        GenerationPipelineRequest request = request("agent-task-1");
        when(service.execute(eq("agent-task-1"), any(), any(), eq(request.workspace()))).thenReturn(
                new AgentEditResult("agent-task-1", "agent_edit", "failed", List.of(), "failed", 1));

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationPipelineDisposition.COMPLETED, outcome.disposition());
        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("agent_edit_failed", outcome.reason());
        assertTrue(outcome.resultSummary().contains("任务状态：失败"));
        assertTrue(outcome.resultSummary().contains("修复轮次：1"));
        GenerationStreamEvent error = request.execution().session().asFlux()
                .filter(event -> GenerationStreamEvent.GENERATION_ERROR.equals(event.getType()))
                .blockFirst(Duration.ofSeconds(1));
        assertNotNull(error);
        verify(monitor).recordSpan(eq("agent-task-1"), eq("agent_edit_pipeline"),
                eq(GenerationSpanCategory.PIPELINE), eq("failed"), any(), eq("repairRounds=1"));
    }

    @Test
    void successfulAgentEditMustUseSubmissionTaskIdentity() {
        AgentEditGenerationService service = mock(AgentEditGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        AgentEditGenerationPipeline pipeline = new AgentEditGenerationPipeline(service, monitor);
        GenerationPipelineRequest request = request("agent-task-success");
        when(service.execute(eq("agent-task-success"), any(), any(), eq(request.workspace()))).thenReturn(
                new AgentEditResult(
                        "agent-task-success",
                        "agent_edit",
                        "done",
                        List.of("src/App.vue"),
                        "success",
                        0,
                        observedEvidence(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD)));

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationTaskStatus.SUCCESS, outcome.terminalStatus());
        assertTrue(outcome.resultSummary().contains("结果摘要：done"));
        assertTrue(outcome.resultSummary().contains("src/App.vue"));
        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.BUILD_VALIDATION));
        verify(service).execute(eq("agent-task-success"), any(), any(), eq(request.workspace()));
    }

    @Test
    void pipelineMustNotPromoteFastEvidenceToExpectedBuildEvidence() {
        AgentEditGenerationService service = mock(AgentEditGenerationService.class);
        AgentEditGenerationPipeline pipeline = new AgentEditGenerationPipeline(
                service, mock(GenerationPerformanceMonitorService.class));
        GenerationPipelineRequest request = request("agent-task-fast-only");
        when(service.execute(eq("agent-task-fast-only"), any(), any(), eq(request.workspace())))
                .thenReturn(new AgentEditResult(
                        "agent-task-fast-only",
                        "agent_edit",
                        "done",
                        List.of("src/App.vue"),
                        "success",
                        0,
                        observedEvidence(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK)));

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertTrue(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.FAST_VALIDATION));
        assertFalse(outcome.completionEvidence().contains(
                GenerationCompletionEvidenceType.BUILD_VALIDATION));
    }

    @Test
    void executionPolicyFailureMustReachTheUnifiedTerminalBoundary() {
        AgentEditGenerationService service = mock(AgentEditGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        AgentEditGenerationPipeline pipeline = new AgentEditGenerationPipeline(service, monitor);
        GenerationPipelineRequest request = request("agent-task-deadline");
        when(service.execute(eq("agent-task-deadline"), any(), any(), eq(request.workspace())))
                .thenThrow(new GenerationDeadlineExceededException("agent-task-deadline"));

        assertThrows(GenerationDeadlineExceededException.class, () -> pipeline.execute(request));
    }

    private GenerationCompletionEvidenceSet observedEvidence(
            GenerationExecutionPlan.ValidationStep first,
            GenerationExecutionPlan.ValidationStep... additional) {
        java.util.EnumSet<GenerationExecutionPlan.ValidationStep> steps =
                java.util.EnumSet.of(first, additional);
        return ObservedValidationCompletionEvidenceFactory.forCompletedMutation(
                1,
                GenerationValidationObservation.passed(
                        CodeGenTypeEnum.VUE_PROJECT,
                        "agent_edit_validator",
                        steps,
                        Map.of())
        );
    }

    private GenerationPipelineRequest request(String taskId) {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/test-workspace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, root, root, true, root, root, Set.of(), Set.of());
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT, 0.82, "test agent edit",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, ExpectedValidationLevel.BUILD);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "修改登录页标题", user);
        GenerationExecutionContext context = new GenerationExecutionContext(
                taskId, 1L, 2L, Instant.now(), new GenerationRuntimeProperties().toLimits(), Clock.systemUTC());
        GenerationExecutionFence fence = new GenerationExecutionFence(taskId, "worker-a", 3L);
        context.bindExecutionFence(fence);
        GenerationSession session = new GenerationSession(null, context);
        return new GenerationPipelineRequest(
                taskRequest, CodeGenTypeEnum.VUE_PROJECT, workspace, decision,
                new GenerationTaskExecution(taskId, session, context, fence, Instant.now()));
    }
}
