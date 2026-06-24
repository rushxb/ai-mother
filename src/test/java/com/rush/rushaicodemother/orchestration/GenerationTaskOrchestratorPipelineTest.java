package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerationTaskOrchestratorPipelineTest {

    @Test
    void shouldDispatchOnlyToPipelineAssignedByRouter() {
        TestContext context = testContext();
        StubPipeline lightPipeline = new StubPipeline(
                "lightweight_edit",
                GenerationMode.LIGHT_EDIT,
                Optional.of(new GenerationTaskResult("task-1", "lightweight_edit", context.workspace(), Flux.empty()))
        );
        StubPipeline createPipeline = new StubPipeline(
                "create",
                GenerationMode.CREATE,
                Optional.of(new GenerationTaskResult("task-2", "create", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                lightEditDecision(),
                List.of(createPipeline, lightPipeline)
        );

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals("lightweight_edit", result.route());
        assertEquals(0, createPipeline.executeCount);
        assertEquals(1, lightPipeline.executeCount);
    }

    @Test
    void shouldRejectRoutedPipelineFailureWhenFallbackIsDisabled() {
        TestContext context = testContext();
        StubPipeline failedPipeline = new StubPipeline("lightweight_edit", GenerationMode.LIGHT_EDIT, Optional.empty());
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                lightEditDecision(),
                List.of(failedPipeline)
        );

        assertThrows(BusinessException.class, () -> orchestrator.start(context.request()));
        assertEquals(1, failedPipeline.executeCount);
    }

    @Test
    void shouldNotEscalateCreatePipelineFailureToHeavyBeforeBuildValidation() {
        TestContext context = testContext(false);
        StubPipeline failedCreatePipeline = new StubPipeline("create", GenerationMode.CREATE, Optional.empty());
        StubPipeline heavyPipeline = new StubPipeline(
                "heavy_generation",
                GenerationMode.HEAVY_EXPERT,
                Optional.of(new GenerationTaskResult("heavy-1", "heavy_generation", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                createDecisionWithLegacyFallback(),
                List.of(failedCreatePipeline, heavyPipeline)
        );

        assertThrows(BusinessException.class, () -> orchestrator.start(context.request()));
        assertEquals(1, failedCreatePipeline.executeCount);
        assertEquals(0, heavyPipeline.executeCount);
    }

    @Test
    void shouldNotEscalateAgentEditUpstreamTimeoutToHeavy() {
        TestContext context = testContext();
        StubPipeline timeoutAgentEditPipeline = new StubPipeline(
                "agent_edit",
                GenerationMode.AGENT_EDIT,
                Optional.of(new GenerationTaskResult("agent-timeout", "agent_edit", context.workspace(), Flux.empty()))
        );
        StubPipeline heavyPipeline = new StubPipeline(
                "heavy_generation",
                GenerationMode.HEAVY_EXPERT,
                Optional.of(new GenerationTaskResult("heavy-1", "heavy_generation", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                agentEditDecision(),
                List.of(timeoutAgentEditPipeline, heavyPipeline)
        );

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals("agent_edit", result.route());
        assertEquals(1, timeoutAgentEditPipeline.executeCount);
        assertEquals(0, heavyPipeline.executeCount);
    }

    @Test
    void shouldRejectNewPipelineWhenAppHasActiveSession() {
        TestContext context = testContext();
        GenerationSessionRegistry sessionRegistry = new GenerationSessionRegistry();
        sessionRegistry.put(context.app().getId(), new GenerationSession(null));
        StubPipeline pipeline = new StubPipeline(
                "lightweight_edit",
                GenerationMode.LIGHT_EDIT,
                Optional.of(new GenerationTaskResult("task-1", "lightweight_edit", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                lightEditDecision(),
                List.of(pipeline),
                sessionRegistry
        );

        assertThrows(BusinessException.class, () -> orchestrator.start(context.request()));
        assertEquals(0, pipeline.executeCount);
    }

    @Test
    void shouldAllowNewPipelineWhenStoredSessionIsCompletedReplay() {
        TestContext context = testContext();
        GenerationSession completedSession = new GenerationSession(null);
        completedSession.complete();
        assertFalse(completedSession.isActive());
        GenerationSessionRegistry sessionRegistry = new GenerationSessionRegistry();
        sessionRegistry.put(context.app().getId(), completedSession);
        StubPipeline pipeline = new StubPipeline(
                "lightweight_edit",
                GenerationMode.LIGHT_EDIT,
                Optional.of(new GenerationTaskResult("task-1", "lightweight_edit", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context,
                lightEditDecision(),
                List.of(pipeline),
                sessionRegistry
        );

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals("lightweight_edit", result.route());
        assertEquals(1, pipeline.executeCount);
    }

    private GenerationTaskOrchestrator newOrchestrator(TestContext context,
                                                       GenerationModeDecision decision,
                                                       List<GenerationPipeline> pipelines) {
        return newOrchestrator(context, decision, pipelines, new GenerationSessionRegistry());
    }

    private GenerationTaskOrchestrator newOrchestrator(TestContext context,
                                                       GenerationModeDecision decision,
                                                       List<GenerationPipeline> pipelines,
                                                       GenerationSessionRegistry sessionRegistry) {
        GenerationModeRouter modeRouter = mock(GenerationModeRouter.class);
        when(modeRouter.route(context.request(), CodeGenTypeEnum.VUE_PROJECT, context.workspace())).thenReturn(decision);
        return new GenerationTaskOrchestrator(
                null,
                null,
                pipelines,
                sessionRegistry,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                modeRouter,
                context.workspaceService()
        );
    }

    private TestContext testContext() {
        return testContext(true);
    }

    private TestContext testContext(boolean workspaceExists) {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        Path rootPath = Path.of("target/test-workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                rootPath,
                rootPath,
                workspaceExists,
                rootPath,
                rootPath,
                Set.of(),
                Set.of()
        );
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        GenerationTaskRequest request = new GenerationTaskRequest(app, "优化首页", user);
        when(workspaceService.resolve(app, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(workspace);
        return new TestContext(workspaceService, workspace, app, request);
    }

    private GenerationModeDecision lightEditDecision() {
        return GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT,
                0.9,
                "test route",
                FallbackPolicy.NONE,
                ExpectedValidationLevel.FAST
        );
    }

    private GenerationModeDecision createDecisionWithLegacyFallback() {
        return GenerationModeDecision.of(
                GenerationMode.CREATE,
                0.9,
                "missing workspace",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
    }

    private GenerationModeDecision agentEditDecision() {
        return GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.82,
                "existing workspace requires agent edit",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD
        );
    }

    private static class StubPipeline implements GenerationPipeline {

        private final String route;
        private final GenerationMode mode;
        private final Optional<GenerationTaskResult> result;
        private int executeCount;

        private StubPipeline(String route, GenerationMode mode, Optional<GenerationTaskResult> result) {
            this.route = route;
            this.mode = mode;
            this.result = result;
        }

        @Override
        public String route() {
            return route;
        }

        @Override
        public boolean supports(GenerationPipelineRequest request) {
            return request.modeIs(mode);
        }

        @Override
        public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
            executeCount++;
            return result;
        }
    }

    private record TestContext(GenerationWorkspaceService workspaceService,
                               GenerationWorkspace workspace,
                               App app,
                               GenerationTaskRequest request) {
    }
}
