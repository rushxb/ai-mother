package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskIdempotency;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;
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
    void shouldDelegateUnfrozenRequestSoSubmissionCanCreateIdentityBeforePreflight() {
        TestContext context = testContext();
        GenerationTaskSubmissionService submissionService = mock(GenerationTaskSubmissionService.class);
        Instant submittedAt = Instant.parse("2026-07-20T10:00:00Z");
        GenerationTaskResult expected = new GenerationTaskResult(
                new GenerationTaskSubmissionReceipt(
                        "task-1", 1L, "lightweight_edit", GenerationTaskStatus.QUEUED,
                        submittedAt, submittedAt.plusSeconds(600)),
                context.workspace(), Flux.empty());
        when(submissionService.submit(
                context.request(),
                CodeGenTypeEnum.VUE_PROJECT,
                context.workspace(),
                GenerationTaskIdempotency.none())).thenReturn(expected);
        GenerationTaskOrchestrator orchestrator = new GenerationTaskOrchestrator(
                context.workspaceService(), submissionService,
                mock(GenerationTaskControlService.class));

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals(expected, result);
        verify(submissionService).submit(
                context.request(),
                CodeGenTypeEnum.VUE_PROJECT,
                context.workspace(),
                GenerationTaskIdempotency.none());
    }

    @Test
    void shouldRejectBlankPromptBeforeSubmission() {
        TestContext context = testContext();
        GenerationTaskSubmissionService submissionService = mock(GenerationTaskSubmissionService.class);
        GenerationTaskOrchestrator orchestrator = new GenerationTaskOrchestrator(
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

    private record TestContext(GenerationWorkspaceService workspaceService,
                               GenerationWorkspace workspace,
                               GenerationTaskRequest request) {
    }
}
