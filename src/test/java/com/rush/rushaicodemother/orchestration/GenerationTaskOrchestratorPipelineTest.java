package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.decision.GenerationMutability;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecision;
import com.rush.rushaicodemother.orchestration.decision.GenerationScenarioDecisionKernel;
import com.rush.rushaicodemother.orchestration.decision.GenerationToolPermissionProfile;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.pipeline.GenerationPipelineRequest;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.FallbackPolicy;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskOrchestratorPipelineTest {

    @Test
    void shouldResolveRouteMetadataAndDelegateToSubmissionRuntime() {
        TestContext context = testContext();
        GenerationScenarioDecisionKernel decisionKernel = mock(GenerationScenarioDecisionKernel.class);
        GenerationTaskSubmissionService submissionService = mock(GenerationTaskSubmissionService.class);
        GenerationSessionRegistry registry = new GenerationSessionRegistry(new GenerationSessionProperties());
        GenerationModeDecision decision = lightEditDecision();
        IntentProfile intentProfile = new IntentProfile(
                com.rush.rushaicodemother.orchestration.intent.IntentOperationType.EDIT,
                Set.of(IntentAffectedScope.DATABASE),
                com.rush.rushaicodemother.orchestration.intent.IntentSemanticComplexity.MEDIUM,
                true,
                true,
                com.rush.rushaicodemother.orchestration.intent.IntentDestructiveRisk.LOW,
                3,
                com.rush.rushaicodemother.orchestration.intent.IntentValidationRisk.MEDIUM,
                0.9);
        GenerationScenarioDecision scenarioDecision = new GenerationScenarioDecision(
                intentProfile,
                CodeGenTypeEnum.VUE_PROJECT,
                GenerationMutability.WRITE,
                GenerationResourceRequirements.ofDatabaseRequirement(true),
                decision,
                GenerationToolPermissionProfile.WRITE_FENCED,
                "intent-lexical/test",
                "a".repeat(64));
        when(decisionKernel.decide(
                context.request(), CodeGenTypeEnum.VUE_PROJECT, context.workspace()))
                .thenReturn(scenarioDecision);
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
                decisionKernel, context.workspaceService(), submissionService,
                mock(GenerationTaskControlService.class));

        GenerationTaskResult result = orchestrator.start(context.request());

        assertEquals(expected, result);
        ArgumentCaptor<GenerationPipelineRequest> captor = ArgumentCaptor.forClass(GenerationPipelineRequest.class);
        verify(submissionService).submit(
                captor.capture(), org.mockito.ArgumentMatchers.eq(GenerationTaskIdempotency.none()));
        assertSame(scenarioDecision, captor.getValue().scenarioDecision());
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
                mock(GenerationScenarioDecisionKernel.class),
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
                mock(GenerationScenarioDecisionKernel.class),
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
