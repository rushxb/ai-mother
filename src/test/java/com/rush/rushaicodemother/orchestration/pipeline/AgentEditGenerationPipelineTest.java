package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.edit.AgentEditGenerationService;
import com.rush.rushaicodemother.orchestration.edit.AgentEditResult;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentEditGenerationPipelineTest {

    @Test
    void shouldReturnEmptyWhenAgentEditFailsSoOrchestratorCanApplyFallbackPolicy() {
        AgentEditGenerationService service = mock(AgentEditGenerationService.class);
        GenerationPerformanceMonitorService monitor = mock(GenerationPerformanceMonitorService.class);
        AgentEditGenerationPipeline pipeline = new AgentEditGenerationPipeline(
                service,
                monitor,
                new GenerationSessionRegistry()
        );
        GenerationPipelineRequest request = request();
        when(service.execute(any(), any())).thenReturn(
                new AgentEditResult("agent-task-1", "agent_edit", "failed", List.of(), "failed", 1)
        );

        assertTrue(pipeline.execute(request).isEmpty());
        verify(monitor).recordSpan(
                org.mockito.ArgumentMatchers.eq("agent-task-1"),
                org.mockito.ArgumentMatchers.eq("agent_edit_pipeline"),
                org.mockito.ArgumentMatchers.eq("failed"),
                any(),
                org.mockito.ArgumentMatchers.eq("repairRounds=1")
        );
    }

    private GenerationPipelineRequest request() {
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        Path root = Path.of("target/test-workspace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                root,
                root,
                true,
                root,
                root,
                Set.of(),
                Set.of()
        );
        GenerationModeDecision decision = GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.82,
                "test agent edit",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
        return new GenerationPipelineRequest(
                new GenerationTaskRequest(app, "新增搜索分页", user),
                CodeGenTypeEnum.VUE_PROJECT,
                workspace,
                decision
        );
    }
}
