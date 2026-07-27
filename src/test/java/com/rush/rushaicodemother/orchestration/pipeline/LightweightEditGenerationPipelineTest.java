package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditResult;
import com.rush.rushaicodemother.orchestration.edit.LightweightEditService;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskExecution;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LightweightEditGenerationPipelineTest {

    @Test
    void successfulEditMustReturnSearchableTerminalSummary() {
        LightweightEditService service = mock(LightweightEditService.class);
        LightweightEditGenerationPipeline pipeline = new LightweightEditGenerationPipeline(
                mock(GenerationPerformanceMonitorService.class), service);
        GenerationPipelineRequest request = request("light-task-success");
        when(service.execute(eq("light-task-success"), any(), eq(request.workspace())))
                .thenReturn(new LightweightEditResult(
                        "light-task-success", "lightweight_edit", "标题已更新",
                        List.of("src/App.vue"), "applied"));

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationTaskStatus.SUCCESS, outcome.terminalStatus());
        assertNull(outcome.reason());
        assertTrue(outcome.resultSummary().contains("结果摘要：标题已更新"));
        assertTrue(outcome.resultSummary().contains("src/App.vue"));
    }

    @Test
    void failedEditMustReturnFailureReasonAndLessonSummary() {
        LightweightEditService service = mock(LightweightEditService.class);
        LightweightEditGenerationPipeline pipeline = new LightweightEditGenerationPipeline(
                mock(GenerationPerformanceMonitorService.class), service);
        GenerationPipelineRequest request = request("light-task-failed");
        when(service.execute(eq("light-task-failed"), any(), eq(request.workspace())))
                .thenReturn(new LightweightEditResult(
                        "light-task-failed", "lightweight_edit", "补丁验证失败",
                        List.of(), "failed"));

        GenerationPipelineOutcome outcome = pipeline.execute(request);

        assertEquals(GenerationTaskStatus.FAILED, outcome.terminalStatus());
        assertEquals("lightweight_edit_failed", outcome.reason());
        assertTrue(outcome.resultSummary().contains("任务状态：失败"));
        assertTrue(outcome.resultSummary().contains("补丁验证失败"));
    }

    @Test
    void executionPolicyFailureMustReachTheUnifiedTerminalBoundary() {
        LightweightEditService service = mock(LightweightEditService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        LightweightEditGenerationPipeline pipeline = new LightweightEditGenerationPipeline(monitor, service);
        GenerationPipelineRequest request = request("light-task-deadline");
        when(service.execute(eq("light-task-deadline"), any(), eq(request.workspace())))
                .thenThrow(new GenerationDeadlineExceededException("light-task-deadline"));

        assertThrows(GenerationDeadlineExceededException.class, () -> pipeline.execute(request));
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
                GenerationMode.LIGHT_EDIT, 0.92, "test lightweight edit",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT, ExpectedValidationLevel.BUILD);
        GenerationTaskRequest taskRequest = new GenerationTaskRequest(app, "update title", user);
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
