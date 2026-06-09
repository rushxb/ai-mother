package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipeline;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
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
    void shouldDispatchToFirstPipelineThatReturnsResult() {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        Path rootPath = Path.of("target/test-workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                rootPath,
                rootPath,
                false,
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

        StubPipeline emptyPipeline = new StubPipeline("empty", Optional.empty());
        StubPipeline handledPipeline = new StubPipeline(
                "handled",
                Optional.of(new GenerationTaskResult("task-1", "handled", workspace, Flux.empty()))
        );
        StubPipeline skippedPipeline = new StubPipeline(
                "skipped",
                Optional.of(new GenerationTaskResult("task-2", "skipped", workspace, Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                workspaceService,
                List.of(emptyPipeline, handledPipeline, skippedPipeline)
        );

        GenerationTaskResult result = orchestrator.start(request);

        assertEquals("handled", result.route());
        assertEquals(1, emptyPipeline.executeCount);
        assertEquals(1, handledPipeline.executeCount);
        assertEquals(0, skippedPipeline.executeCount);
    }

    @Test
    void shouldRejectNewPipelineWhenAppHasActiveSession() {
        TestContext context = testContext();
        GenerationSessionRegistry sessionRegistry = new GenerationSessionRegistry();
        sessionRegistry.put(context.app().getId(), new GenerationSession(null));
        StubPipeline pipeline = new StubPipeline(
                "handled",
                Optional.of(new GenerationTaskResult("task-1", "handled", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context.workspaceService(),
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
                "handled",
                Optional.of(new GenerationTaskResult("task-1", "handled", context.workspace(), Flux.empty()))
        );
        GenerationTaskOrchestrator orchestrator = newOrchestrator(
                context.workspaceService(),
                List.of(pipeline),
                sessionRegistry
        );

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals("handled", result.route());
        assertEquals(1, pipeline.executeCount);
    }

    private GenerationTaskOrchestrator newOrchestrator(GenerationWorkspaceService workspaceService,
                                                       List<GenerationPipeline> pipelines) {
        return newOrchestrator(workspaceService, pipelines, new GenerationSessionRegistry());
    }

    private GenerationTaskOrchestrator newOrchestrator(GenerationWorkspaceService workspaceService,
                                                       List<GenerationPipeline> pipelines,
                                                       GenerationSessionRegistry sessionRegistry) {
        return new GenerationTaskOrchestrator(
                null,  // generationAppStateService
                null,  // generationEventPublisher
                pipelines,
                sessionRegistry,
                null,  // heavyGenerationBuildValidationService
                null,  // heavyGenerationExecutionService
                null,  // heavyGenerationFailureRecoveryService
                null,  // heavyGenerationFinalizationService
                null,  // heavyGenerationPreparationService
                null,  // heavyGenerationSessionCompletionService
                null,  // generationTaskLifecycleService
                null,  // generationToolExecutionContextService
                null,  // generationTraceService
                workspaceService
        );
    }

    private TestContext testContext() {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        Path rootPath = Path.of("target/test-workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                rootPath,
                rootPath,
                false,
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

    private static class StubPipeline implements GenerationPipeline {

        private final String route;
        private final Optional<GenerationTaskResult> result;
        private int executeCount;

        private StubPipeline(String route, Optional<GenerationTaskResult> result) {
            this.route = route;
            this.result = result;
        }

        @Override
        public String route() {
            return route;
        }

        @Override
        public boolean supports(GenerationPipelineRequest request) {
            return true;
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
