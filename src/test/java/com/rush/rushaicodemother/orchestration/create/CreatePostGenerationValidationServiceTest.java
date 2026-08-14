package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.heavy.HeavyGenerationBuildValidationService;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.verification.GenerationValidationObservation;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationExecutionWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreatePostGenerationValidationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void successfulValidatorWithoutObservationMustFailClosed() {
        GenerationToolExecutionContextService contextService =
                mock(GenerationToolExecutionContextService.class);
        HeavyGenerationBuildValidationService buildValidationService =
                mock(HeavyGenerationBuildValidationService.class);
        CreatePostGenerationValidationService service = new CreatePostGenerationValidationService(
                contextService, buildValidationService);
        GenerationSession session = new GenerationSession(null);
        User user = User.builder().id(7L).build();
        when(buildValidationService.runWithAutoRepair(
                eq(14L), eq(user), any(), eq(session))).thenReturn(true);

        CreatePostGenerationValidationService.ValidationOutcome outcome = service.validate(
                14L,
                user,
                CodeGenTypeEnum.VUE_PROJECT,
                "创建仪表盘",
                "task-missing-observation",
                null,
                session
        );

        assertFalse(outcome.success());
        assertEquals("create_validation_evidence_missing", outcome.reason());
    }

    @Test
    void managedValidationMustKeepExactFenceAndWorkspaceDuringRepair() {
        GenerationToolExecutionContextService contextService =
                mock(GenerationToolExecutionContextService.class);
        HeavyGenerationBuildValidationService buildValidationService =
                mock(HeavyGenerationBuildValidationService.class);
        CreatePostGenerationValidationService service = new CreatePostGenerationValidationService(
                contextService, buildValidationService);
        GenerationExecutionFence fence = new GenerationExecutionFence("task-create", "worker-a", 7L);
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        when(executionContext.taskId()).thenReturn(fence.taskId());
        when(executionContext.executionFence()).thenReturn(fence);
        GenerationSession session = new GenerationSession(null, executionContext);
        GenerationExecutionWorkspace executionWorkspace = executionWorkspace(fence);
        session.bindExecutionWorkspace(executionWorkspace);
        when(buildValidationService.runWithAutoRepair(
                eq(11L), any(User.class), any(), eq(session))).thenAnswer(invocation ->
                recordBuildObservation(invocation.getArgument(2), CodeGenTypeEnum.VUE_PROJECT));
        SlotFillResult result = SlotFillResult.success(
                "vue-default",
                List.of("hero"),
                List.of(PatchOperation.add("src/App.vue", "<template />")),
                "created",
                12
        );

        CreatePostGenerationValidationService.ValidationOutcome outcome = service.validate(
                11L,
                new User(),
                CodeGenTypeEnum.VUE_PROJECT,
                "create a dashboard",
                fence.taskId(),
                result,
                session
        );

        assertTrue(outcome.success());
        verify(contextService).bindChangePlan(
                eq(11L),
                eq(fence.taskId()),
                eq("create_build_repair"),
                eq(CodeGenTypeEnum.VUE_PROJECT),
                any(ChangePlan.class),
                eq(true),
                eq("create_post_generation_build_repair"),
                eq(executionWorkspace.workspace()),
                eq(fence)
        );
        verify(contextService).clearContext(11L, fence.taskId(), fence);
        verify(contextService, never()).clearContext(11L, fence.taskId());
    }

    @Test
    void plannedCreateValidationMustPassFrozenPolicyToHeavyValidation() {
        GenerationToolExecutionContextService contextService =
                mock(GenerationToolExecutionContextService.class);
        HeavyGenerationBuildValidationService buildValidationService =
                mock(HeavyGenerationBuildValidationService.class);
        CreatePostGenerationValidationService service = new CreatePostGenerationValidationService(
                contextService, buildValidationService);
        GenerationSession session = new GenerationSession(null);
        User user = new User();
        GenerationExecutionPlan executionPlan = mock(GenerationExecutionPlan.class);
        when(executionPlan.validationGraph()).thenReturn(
                GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD));
        when(buildValidationService.runWithAutoRepair(
                eq(13L),
                eq(user),
                any(),
                eq(session),
                any(GenerationVerificationPolicy.class)))
                .thenAnswer(invocation ->
                        recordBuildObservation(invocation.getArgument(2), CodeGenTypeEnum.VUE_PROJECT));

        CreatePostGenerationValidationService.ValidationOutcome outcome = service.validate(
                13L,
                user,
                CodeGenTypeEnum.VUE_PROJECT,
                "创建仪表盘",
                "task-planned-create",
                null,
                session,
                executionPlan
        );

        assertTrue(outcome.success());
        verify(buildValidationService).runWithAutoRepair(
                eq(13L),
                eq(user),
                any(),
                eq(session),
                any(GenerationVerificationPolicy.class));
    }
    @Test
    void backendCreateMustExecutePostGenerationBuildValidation() {
        GenerationToolExecutionContextService contextService =
                mock(GenerationToolExecutionContextService.class);
        HeavyGenerationBuildValidationService buildValidationService =
                mock(HeavyGenerationBuildValidationService.class);
        CreatePostGenerationValidationService service = new CreatePostGenerationValidationService(
                contextService, buildValidationService);
        GenerationSession session = new GenerationSession(null);
        User user = new User();
        when(buildValidationService.runWithAutoRepair(eq(12L), eq(user), any(), eq(session)))
                .thenAnswer(invocation ->
                        recordBuildObservation(invocation.getArgument(2), CodeGenTypeEnum.BACKEND_PROJECT));

        CreatePostGenerationValidationService.ValidationOutcome outcome = service.validate(
                12L,
                user,
                CodeGenTypeEnum.BACKEND_PROJECT,
                "创建课程管理后端",
                "task-backend-create",
                null,
                session
        );

        assertTrue(outcome.success());
        assertTrue(outcome.executed());
        assertTrue(outcome.observation().passedSteps().contains(
                GenerationExecutionPlan.ValidationStep.BUILD));
        verify(buildValidationService).runWithAutoRepair(eq(12L), eq(user), any(), eq(session));
        verify(contextService).clearContext(12L, "task-backend-create");
    }

    private boolean recordBuildObservation(
            GenerationPreparation preparation,
            CodeGenTypeEnum targetType
    ) {
        GenerationVerificationEvidenceRecorder.recordPassed(
                preparation,
                GenerationValidationObservation.passed(
                        targetType,
                        "create_build_validation",
                        Set.of(
                                GenerationExecutionPlan.ValidationStep.FAST_CHECK,
                                GenerationExecutionPlan.ValidationStep.BUILD),
                        Map.of("stage", "done")));
        return true;
    }

    private GenerationExecutionWorkspace executionWorkspace(GenerationExecutionFence fence) {
        Path epochRoot = tempDir.resolve("epoch-7").toAbsolutePath().normalize();
        Path typeRoot = epochRoot.resolve(CodeGenTypeEnum.VUE_PROJECT.getValue());
        Path workspaceRoot = typeRoot.resolve("workspace");
        GenerationWorkspace workspace = new GenerationWorkspace(
                11L,
                CodeGenTypeEnum.VUE_PROJECT,
                workspaceRoot,
                workspaceRoot,
                false,
                workspaceRoot,
                null,
                Set.of(),
                Set.of()
        );
        return new GenerationExecutionWorkspace(
                11L,
                fence,
                CodeGenTypeEnum.VUE_PROJECT,
                epochRoot,
                typeRoot,
                workspace,
                null
        );
    }
}
