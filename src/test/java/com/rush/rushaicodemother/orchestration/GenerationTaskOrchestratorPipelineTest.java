package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
import com.rush.rushaicodemother.orchestration.router.GenerationModeRouter;
import com.rush.rushaicodemother.orchestration.router.GenerationRouteSelection;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotency;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.time.Instant;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskOrchestratorPipelineTest {

    @Test
    void shouldResolveRouteMetadataAndDelegateToSubmissionRuntime() {
        TestContext context = testContext();
        GenerationModeRouter router = mock(GenerationModeRouter.class);
        GenerationTaskSubmissionService submissionService = mock(GenerationTaskSubmissionService.class);
        GenerationSessionRegistry registry = new GenerationSessionRegistry(new GenerationSessionProperties());
        GenerationModeDecision decision = lightEditDecision();
        IntentProfile intentProfile = IntentProfile.unknown();
        when(router.select(context.request(), CodeGenTypeEnum.VUE_PROJECT, context.workspace()))
                .thenReturn(new GenerationRouteSelection(intentProfile, decision));
        Instant submittedAt = Instant.parse("2026-07-20T10:00:00Z");
        GenerationTaskResult expected = new GenerationTaskResult(
                new GenerationTaskSubmissionReceipt(
                        "task-1", 1L, "lightweight_edit", GenerationTaskStatus.QUEUED,
                        submittedAt, submittedAt.plusSeconds(600)),
                context.workspace(), Flux.empty());
        when(submissionService.submit(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(GenerationTaskIdempotency.none()))).thenReturn(expected);
        GenerationTaskOrchestrator orchestrator = new GenerationTaskOrchestrator(
                registry, router, context.workspaceService(), submissionService,
                mock(GenerationTaskControlService.class));

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals(expected, result);
        ArgumentCaptor<GenerationPipelineRequest> captor = ArgumentCaptor.forClass(GenerationPipelineRequest.class);
        verify(submissionService).submit(
                captor.capture(), org.mockito.ArgumentMatchers.eq(GenerationTaskIdempotency.none()));
        assertEquals(decision, captor.getValue().modeDecision());
        assertEquals(intentProfile, captor.getValue().intentProfile());
        assertEquals(context.workspace(), captor.getValue().workspace());
        assertEquals(null, captor.getValue().execution());
    }

    @Test
    void shouldRejectBlankPromptBeforeSubmission() {
        TestContext context = testContext();
        GenerationTaskSubmissionService submissionService = mock(GenerationTaskSubmissionService.class);
        GenerationTaskOrchestrator orchestrator = new GenerationTaskOrchestrator(
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                mock(GenerationModeRouter.class),
                context.workspaceService(),
                submissionService,
                mock(GenerationTaskControlService.class));
        GenerationTaskRequest invalid = new GenerationTaskRequest(
                context.request().app(), "   ", context.request().loginUser());

        assertThrows(BusinessException.class, () -> orchestrator.start(invalid));
    }

    @Test
    void stopMustDelegateToDurableTaskControlSeam() {
        TestContext context = testContext();
        GenerationTaskControlService controlService = mock(GenerationTaskControlService.class);
        GenerationTaskOrchestrator orchestrator = new GenerationTaskOrchestrator(
                new GenerationSessionRegistry(new GenerationSessionProperties()),
                mock(GenerationModeRouter.class),
                context.workspaceService(),
                mock(GenerationTaskSubmissionService.class),
                controlService);

        orchestrator.stop(1L, context.request().loginUser());

        verify(controlService).cancelActiveForApp(1L, context.request().loginUser());
    }

    private TestContext testContext() {
        GenerationWorkspaceService workspaceService = mock(GenerationWorkspaceService.class);
        Path rootPath = Path.of("target/test-workspace").toAbsolutePath().normalize();
        GenerationWorkspace workspace = new GenerationWorkspace(
                1L, CodeGenTypeEnum.VUE_PROJECT, rootPath, rootPath, true,
                rootPath, rootPath, Set.of(), Set.of());
        App app = new App();
        app.setId(1L);
        app.setCodeGenType(CodeGenTypeEnum.VUE_PROJECT.getValue());
        User user = new User();
        user.setId(2L);
        GenerationTaskRequest request = new GenerationTaskRequest(app, "更新首页标题", user);
        when(workspaceService.resolve(app, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(workspace);
        return new TestContext(workspaceService, workspace, request);
    }

    private GenerationModeDecision lightEditDecision() {
        return GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT, 0.9, "测试路由",
                FallbackPolicy.NONE, ExpectedValidationLevel.FAST);
    }

    private record TestContext(GenerationWorkspaceService workspaceService,
                               GenerationWorkspace workspace,
                               GenerationTaskRequest request) {
    }
}
