package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
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
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LightweightEditGenerationPipelineTest {

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
