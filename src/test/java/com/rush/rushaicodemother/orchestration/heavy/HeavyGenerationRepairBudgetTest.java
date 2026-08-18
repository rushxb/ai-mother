package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
import com.rush.rushaicodemother.core.builder.GoProjectBuilder;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.core.handler.StreamHandlerExecutor;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationPolicy;
import com.rush.rushaicodemother.orchestration.verification.GenerationVerificationEvidenceRecorder;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntime;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeHandle;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedBackendRuntimeObservation;
import com.rush.rushaicodemother.orchestration.verification.runtime.BackendRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.FullStackRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.verification.runtime.GeneratedFullStackRuntimeVerifier;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.devserver.DevServerError;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationPolicy;
import com.rush.rushaicodemother.service.browser.BrowserRuntimeValidationResult;
import com.rush.rushaicodemother.orchestration.preview.GenerationPreviewMilestoneService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class HeavyGenerationRepairBudgetTest {

    @Test
    void generationAndBuildRepairMustShareOneTaskWideBudget() throws Exception {
        long appId = 870_001L;
        String taskId = "shared-repair-budget";
        Path projectPath = projectPath(appId);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
        GenerationPerformanceSelector performanceSelector = mock(GenerationPerformanceSelector.class);
        GenerationPerformanceProfile initialProfile = GenerationPerformanceProfile.speedFirst();
        HeavyGenerationExecutionService generationService = spy(new HeavyGenerationExecutionService(
                mock(AiCodeGeneratorFacade.class),
                mock(ChatHistoryService.class),
                mock(GenerationAppStateService.class),
                memoryContextService,
                mock(GenerationOrchestrationMetricsCollector.class),
                performanceSelector,
                failureRecoveryService,
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                mock(StreamHandlerExecutor.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                stageAdmissionService(),
                new GenerationRuntimeProperties()
        ));
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        User user = User.builder().id(7L).build();
        RuntimeException recoverableFailure = new RuntimeException("temporary provider failure");
        when(performanceSelector.select(anyBoolean(), anyBoolean(), eq(CodeGenTypeEnum.VUE_PROJECT)))
                .thenReturn(initialProfile);
        when(failureRecoveryService.classifyGenerationError(recoverableFailure)).thenReturn(
                new GenerationErrorClassifier.GenerationError("provider", "temporary failure", true));
        when(memoryContextService.buildAutoRepairMemoryContext(appId, taskId, "temporary failure", 1))
                .thenReturn("");
        doThrow(recoverableFailure)
                .doNothing()
                .when(generationService)
                .executeGenerationRound(
                        eq(appId), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), anyString(), same(session),
                        any(StringBuilder.class), any(long[].class), any(GenerationPerformanceProfile.class));

        try {
            createRepairableProject(projectPath);
            generationService.runGenerationWithAutoRepair(appId, user, preparation, session);
            assertEquals(1, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));

            VueProjectBuilder builder = mock(VueProjectBuilder.class);
            when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                    new VueBuildResult(false, "build", projectPath.toString(), "compile failed", null, null));
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder, taskId, appId);

            boolean passed = buildService.runWithAutoRepair(appId, user, preparation, session);

            assertFalse(passed);
            verify(generationService, times(2)).executeGenerationRound(
                    eq(appId), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), anyString(), same(session),
                    any(StringBuilder.class), any(long[].class), any(GenerationPerformanceProfile.class));
            verify(failureRecoveryService).emitBuildFailure(eq(appId), same(preparation), same(session), anyString());
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void buildRepairConsumesBudgetOnlyAfterWorkspaceIsConfirmedRepairable() throws Exception {
        long appId = 870_002L;
        String taskId = "build-repair-budget";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        User user = User.builder().id(7L).build();
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(false, "build", projectPath.toString(), "compile failed", null, null),
                new VueBuildResult(true, "done", projectPath.toString(), "build passed", null, null));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder, taskId, appId);

            boolean passed = buildService.runWithAutoRepair(appId, user, preparation, session);

            assertTrue(passed);
            assertEquals(1, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
            verify(generationService).executeGenerationRound(
                    eq(appId), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), anyString(), same(session),
                    any(StringBuilder.class), any(long[].class));
            verify(failureRecoveryService, never()).emitBuildFailure(
                    eq(appId), same(preparation), same(session), anyString());
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void nullBuildResultMustFailWithControlledSystemError() throws Exception {
        long appId = 870_003L;
        String taskId = "null-build-result";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder, taskId, appId);

            BusinessException exception = assertThrows(
                    BusinessException.class,
                    () -> buildService.runWithAutoRepair(
                            appId, User.builder().id(7L).build(), preparation, session)
            );

            assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
            assertEquals("项目构建服务异常，请稍后重试", exception.getMessage());
            assertTrue(exception.getCause() instanceof IllegalStateException);
            assertEquals(0, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
            verify(generationService, never()).executeGenerationRound(
                    any(), any(), any(), anyString(), any(), any(StringBuilder.class), any(long[].class));
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void missingWorkspaceMustNotConsumeRepairBudgetOrInvokeBuilder() {
        long appId = 870_004L;
        String taskId = "missing-workspace";
        Path projectPath = projectPath(appId);
        FileUtil.del(projectPath.toFile());
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        HeavyGenerationBuildValidationService buildService = buildService(
                generationService, failureRecoveryService, builder, taskId, appId);

        boolean passed = buildService.runWithAutoRepair(
                appId, User.builder().id(7L).build(), preparation, session);

        assertFalse(passed);
        assertEquals(0, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
        verify(builder, never()).buildProjectWithResult(anyString(), anyString());
        verify(failureRecoveryService).emitMissingProjectCode(
                eq(appId), same(preparation), same(session), any());
    }

    @Test
    void fullStackBuildMustUseNormalizedFrontendWorkspace() throws Exception {
        long appId = 870_005L;
        String taskId = "full-stack-frontend-build";
        Path projectRoot = projectRootPath(appId, CodeGenTypeEnum.FULL_STACK_PROJECT);
        Path frontendPath = projectPath(appId, CodeGenTypeEnum.FULL_STACK_PROJECT);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        GenerationPreparation preparation = preparation(taskId, CodeGenTypeEnum.FULL_STACK_PROJECT);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        when(builder.buildProjectWithResult(frontendPath.toString(), taskId)).thenReturn(
                new VueBuildResult(true, "done", frontendPath.toString(), "build passed", null, null));

        try {
            createRepairableProject(frontendPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder, taskId, appId);

            boolean passed = buildService.runWithAutoRepair(
                    appId, User.builder().id(7L).build(), preparation, session);

            assertTrue(passed);
            verify(builder).buildProjectWithResult(frontendPath.toString(), taskId);
            assertEquals(0, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
        } finally {
            FileUtil.del(projectRoot.toFile());
        }
    }

    @Test
    void unmanagedSessionMustNotStartAutomaticBuildRepair() throws Exception {
        long appId = 870_006L;
        String taskId = "unmanaged-session";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation);
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(false, "build", projectPath.toString(), "compile failed", null, null));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder, taskId, appId);

            boolean passed = buildService.runWithAutoRepair(
                    appId, User.builder().id(7L).build(), preparation, session);

            assertFalse(passed);
            verify(generationService, never()).executeGenerationRound(
                    any(), any(), any(), anyString(), any(), any(StringBuilder.class), any(long[].class));
            verify(failureRecoveryService).emitBuildFailure(
                    eq(appId), same(preparation), same(session), anyString());
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void successfulDevServerValidationMustAllowBuildValidationToComplete() throws Exception {
        long appId = 870_007L;
        String taskId = "runtime-ready-order";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        DevServerValidationService validationService = mock(DevServerValidationService.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        User user = User.builder().id(7L).build();
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(true, "done", projectPath.toString(), "build passed", null, null));
        when(validationService.validate(taskId, appId, 7L, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(DevServerValidationResult.passed(taskId, appId, 20));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder,
                    validationService, appId);

            GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.planned(
                    GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.EXPERT));

            assertTrue(buildService.runWithAutoRepair(
                    appId, user, preparation, session, verificationPolicy));
            verify(validationService).validate(taskId, appId, 7L, CodeGenTypeEnum.VUE_PROJECT);
            Map<String, Object> evidence = preparation.artifact(
                    GenerationVerificationEvidenceRecorder.ARTIFACT_KEY).payload();
            assertEquals(
                    List.of("FAST_CHECK", "BUILD", "EXPERT_CHECK"),
                    evidence.get("passedSteps"));
            assertEquals("PASS", ((Map<?, ?>) evidence.get("details")).get("runtimeStatus"));
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void buildPlanMustSkipDevServerRuntimeValidation() throws Exception {
        long appId = 870_010L;
        String taskId = "build-plan-skips-runtime";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        DevServerValidationService validationService = mock(DevServerValidationService.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        User user = User.builder().id(7L).build();
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(true, "done", projectPath.toString(), "build passed", null, null));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder,
                    validationService, appId);
            GenerationVerificationPolicy verificationPolicy = GenerationVerificationPolicy.planned(
                    GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.BUILD));

            assertTrue(buildService.runWithAutoRepair(
                    appId, user, preparation, session, verificationPolicy));
            verifyNoInteractions(validationService);
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void expertBackendMustEarnRuntimeEvidenceFromProcessAndHttpHealth() throws Exception {
        long appId = 870_011L;
        String taskId = "backend-build-observation";
        Path projectPath = projectPath(appId, CodeGenTypeEnum.BACKEND_PROJECT);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        DevServerValidationService runtimeValidationService = mock(DevServerValidationService.class);
        GeneratedBackendRuntime backendRuntime = mock(GeneratedBackendRuntime.class);
        GenerationProjectBuildValidationService projectBuildValidationService =
                mock(GenerationProjectBuildValidationService.class);
        GenerationPreparation preparation = preparation(taskId, CodeGenTypeEnum.BACKEND_PROJECT);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        when(projectBuildValidationService.validate(
                any(GenerationWorkspace.class), eq(CodeGenTypeEnum.BACKEND_PROJECT), eq(taskId)))
                .thenReturn(new ProjectBuildValidationResult(
                        true, "backend", "done", projectPath.toString(),
                        "backend build passed", "go test passed", ""));
        when(backendRuntime.start(projectPath)).thenReturn(new GeneratedBackendRuntimeHandle(
                19_211,
                GeneratedBackendRuntimeObservation.passed(),
                () -> true,
                () -> { }));

        try {
            Files.createDirectories(projectPath.resolve("cmd/server"));
            Files.writeString(projectPath.resolve("go.mod"), "module example.com/generated\n");
            Files.writeString(projectPath.resolve("cmd/server/main.go"), "package main\nfunc main() {}\n");
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService,
                    failureRecoveryService,
                    runtimeValidationService,
                    new GeneratedBackendRuntimeVerifier(backendRuntime),
                    projectBuildValidationService,
                    appId);
            GenerationVerificationPolicy expertPolicy = GenerationVerificationPolicy.planned(
                    GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.EXPERT));

            assertTrue(buildService.runWithAutoRepair(
                    appId, User.builder().id(7L).build(), preparation, session, expertPolicy));

            Map<String, Object> evidence = preparation.artifact(
                    GenerationVerificationEvidenceRecorder.ARTIFACT_KEY).payload();
            assertEquals(List.of("FAST_CHECK", "BUILD", "EXPERT_CHECK"), evidence.get("passedSteps"));
            assertEquals(
                    "backend_http_health",
                    ((Map<?, ?>) evidence.get("details")).get("runtimeKind"));
            verifyNoInteractions(runtimeValidationService);
            verify(backendRuntime).start(projectPath);
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void unhealthyBackendRuntimeMustBlockExpertCompletionEvidence() throws Exception {
        long appId = 870_012L;
        String taskId = "backend-runtime-unhealthy";
        Path projectPath = projectPath(appId, CodeGenTypeEnum.BACKEND_PROJECT);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        DevServerValidationService frontendRuntime = mock(DevServerValidationService.class);
        GeneratedBackendRuntime backendRuntime = mock(GeneratedBackendRuntime.class);
        GenerationProjectBuildValidationService buildValidation =
                mock(GenerationProjectBuildValidationService.class);
        GenerationPreparation preparation = preparation(taskId, CodeGenTypeEnum.BACKEND_PROJECT);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        session.consumeBudget(GenerationBudgetKind.REPAIR_ROUND);
        when(buildValidation.validate(
                any(GenerationWorkspace.class), eq(CodeGenTypeEnum.BACKEND_PROJECT), eq(taskId)))
                .thenReturn(new ProjectBuildValidationResult(
                        true, "backend", "done", projectPath.toString(),
                        "backend build passed", "go test passed", ""));
        when(backendRuntime.start(projectPath)).thenReturn(GeneratedBackendRuntimeHandle.failed(
                GeneratedBackendRuntimeObservation.failed("backend_health_status_invalid")));

        try {
            Files.createDirectories(projectPath.resolve("cmd/server"));
            Files.writeString(projectPath.resolve("go.mod"), "module example.com/generated\n");
            Files.writeString(projectPath.resolve("cmd/server/main.go"), "package main\nfunc main() {}\n");
            HeavyGenerationBuildValidationService service = buildService(
                    generationService,
                    failureRecoveryService,
                    frontendRuntime,
                    new GeneratedBackendRuntimeVerifier(backendRuntime),
                    buildValidation,
                    appId);
            GenerationVerificationPolicy expertPolicy = GenerationVerificationPolicy.planned(
                    GenerationExecutionPlan.ValidationGraph.forLevel(ExpectedValidationLevel.EXPERT));

            assertFalse(service.runWithAutoRepair(
                    appId, User.builder().id(7L).build(), preparation, session, expertPolicy));

            assertNull(preparation.artifact(GenerationVerificationEvidenceRecorder.ARTIFACT_KEY));
            ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
            verify(failureRecoveryService).emitBuildFailure(
                    eq(appId), same(preparation), same(session), summary.capture());
            assertTrue(summary.getValue().contains("backend_health_status_invalid"));
            verifyNoInteractions(frontendRuntime);
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void fullStackBrowserNetworkFailureMustBlockExpertCompletionEvidence() throws Exception {
        long appId = 870_013L;
        String taskId = "fullstack-browser-network-failure";
        Path projectRoot = projectRootPath(appId, CodeGenTypeEnum.FULL_STACK_PROJECT);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService =
                mock(HeavyGenerationFailureRecoveryService.class);
        DevServerValidationService standaloneFrontendRuntime =
                mock(DevServerValidationService.class);
        GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        GenerationProjectBuildValidationService buildValidation =
                mock(GenerationProjectBuildValidationService.class);
        GenerationPreparation preparation = preparation(
                taskId, CodeGenTypeEnum.FULL_STACK_PROJECT);
        GenerationSession session = new GenerationSession(
                preparation, executionContext(taskId, appId, 1));
        session.consumeBudget(GenerationBudgetKind.REPAIR_ROUND);
        when(buildValidation.validate(
                any(GenerationWorkspace.class),
                eq(CodeGenTypeEnum.FULL_STACK_PROJECT),
                eq(taskId)
        )).thenReturn(new ProjectBuildValidationResult(
                true,
                "fullstack",
                "done",
                projectRoot.toString(),
                "fullstack build passed",
                "frontend and backend build passed",
                ""
        ));
        BrowserRuntimeValidationResult browserFailure =
                BrowserRuntimeValidationResult.failed(10, "browser_network_error");
        DevServerValidationResult frontendFailure = DevServerValidationResult
                .passed(taskId, appId, 10)
                .withBrowserValidation(browserFailure);
        when(fullStackRuntimeVerifier.verify(
                any(Path.class),
                any(),
                any(BrowserRuntimeValidationPolicy.class)
        )).thenReturn(new FullStackRuntimeValidationResult(
                new BackendRuntimeValidationResult(
                        19_101, true, 10,
                        "go run -mod=readonly ./cmd/server", List.of()),
                frontendFailure,
                20
        ));

        try {
            createRepairableProject(projectRoot.resolve("frontend"));
            Files.createDirectories(projectRoot.resolve("backend"));
            HeavyGenerationBuildValidationService service = buildService(
                    generationService,
                    failureRecoveryService,
                    standaloneFrontendRuntime,
                    mock(GeneratedBackendRuntimeVerifier.class),
                    fullStackRuntimeVerifier,
                    buildValidation,
                    appId
            );
            GenerationVerificationPolicy expertPolicy = GenerationVerificationPolicy.planned(
                    GenerationExecutionPlan.ValidationGraph.forLevel(
                            ExpectedValidationLevel.EXPERT));

            assertFalse(service.runWithAutoRepair(
                    appId,
                    User.builder().id(7L).build(),
                    preparation,
                    session,
                    expertPolicy
            ));

            assertNull(preparation.artifact(
                    GenerationVerificationEvidenceRecorder.ARTIFACT_KEY));
            verify(fullStackRuntimeVerifier).verify(
                    eq(projectRoot.resolve("backend")),
                    any(),
                    any(BrowserRuntimeValidationPolicy.class)
            );
            verifyNoInteractions(standaloneFrontendRuntime);
        } finally {
            FileUtil.del(projectRoot.toFile());
        }
    }

    @Test
    void failedDevServerValidationMustEnterRepairLoopWithStructuredDiagnostic() throws Exception {
        long appId = 870_008L;
        String taskId = "runtime-auto-repair";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        DevServerValidationService validationService = mock(DevServerValidationService.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        User user = User.builder().id(7L).build();
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(true, "done", projectPath.toString(), "build passed", null, null),
                new VueBuildResult(true, "done", projectPath.toString(), "build passed after repair", null, null));
        DevServerError runtimeError = DevServerError.tryMatch(
                "[vite] Pre-transform error: Failed to resolve import \"missing-lib\" from \"src/main.ts\"");
        when(validationService.validate(taskId, appId, 7L, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(
                        DevServerValidationResult.failed(taskId, appId, List.of(runtimeError), 20),
                        DevServerValidationResult.passed(taskId, appId, 15));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder,
                    validationService, appId);

            assertTrue(buildService.runWithAutoRepair(appId, user, preparation, session));

            assertEquals(1, session.executionContext().used(GenerationBudgetKind.REPAIR_ROUND));
            verify(generationService).executeGenerationRound(
                    eq(appId), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), anyString(), same(session),
                    any(StringBuilder.class), any(long[].class));
            ArgumentCaptor<Exception> failureCaptor = ArgumentCaptor.forClass(Exception.class);
            verify(generationService).buildAutoRepairPrompt(
                    eq(appId), same(preparation), failureCaptor.capture(), eq(1));
            String repairDiagnostic = failureCaptor.getValue().getMessage();
            assertTrue(repairDiagnostic.contains("validationStage=runtime"));
            assertTrue(repairDiagnostic.contains("status=FAILED"));
            assertTrue(repairDiagnostic.contains("failureKind=RUNTIME_ERROR"));
            assertTrue(repairDiagnostic.contains("missing-lib"));
            assertTrue(repairDiagnostic.contains("检查 package.json"));
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    @Test
    void exhaustedRuntimeRepairMustReportRuntimeFailureInsteadOfSuccessfulBuild() throws Exception {
        long appId = 870_009L;
        String taskId = "runtime-repair-exhausted";
        Path projectPath = projectPath(appId);
        HeavyGenerationExecutionService generationService = mock(HeavyGenerationExecutionService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        VueProjectBuilder builder = mock(VueProjectBuilder.class);
        DevServerValidationService validationService = mock(DevServerValidationService.class);
        GenerationPreparation preparation = preparation(taskId);
        GenerationSession session = new GenerationSession(preparation, executionContext(taskId, appId, 1));
        when(builder.buildProjectWithResult(projectPath.toString(), taskId)).thenReturn(
                new VueBuildResult(true, "done", projectPath.toString(), "build passed", null, null),
                new VueBuildResult(true, "done", projectPath.toString(), "build passed after repair", null, null));
        when(validationService.validate(taskId, appId, 7L, CodeGenTypeEnum.VUE_PROJECT))
                .thenReturn(
                        DevServerValidationResult.startupFailed(taskId, appId, 20, "health check failed"),
                        DevServerValidationResult.startupFailed(taskId, appId, 20, "health check still failed"));

        try {
            createRepairableProject(projectPath);
            HeavyGenerationBuildValidationService buildService = buildService(
                    generationService, failureRecoveryService, builder,
                    validationService, appId);

            assertFalse(buildService.runWithAutoRepair(
                    appId, User.builder().id(7L).build(), preparation, session));

            ArgumentCaptor<String> summaryCaptor = ArgumentCaptor.forClass(String.class);
            verify(failureRecoveryService).emitBuildFailure(
                    eq(appId), same(preparation), same(session), summaryCaptor.capture());
            assertTrue(summaryCaptor.getValue().contains("Dev Server 启动失败"));
            assertFalse(summaryCaptor.getValue().contains("build passed"));
        } finally {
            FileUtil.del(projectPath.toFile());
        }
    }

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            VueProjectBuilder builder,
            String taskId,
            long appId
    ) {
        DevServerValidationService devServerValidationService = mock(DevServerValidationService.class);
        when(devServerValidationService.validate(eq(taskId), eq(appId), eq(7L), any(CodeGenTypeEnum.class)))
                .thenReturn(DevServerValidationResult.passed(taskId, appId, 1));
        return buildService(
                generationService,
                failureRecoveryService,
                builder,
                devServerValidationService,
                appId
        );
    }

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            VueProjectBuilder builder,
            DevServerValidationService devServerValidationService,
            long appId
    ) {
        doReturn("repair prompt").when(generationService).buildAutoRepairPrompt(
                eq(appId), any(GenerationPreparation.class), any(Exception.class), anyInt());
        GenerationProjectBuildValidationService projectBuildValidationService =
                mock(GenerationProjectBuildValidationService.class);
        when(projectBuildValidationService.validate(
                any(GenerationWorkspace.class), any(CodeGenTypeEnum.class), anyString()))
                .thenAnswer(invocation -> {
                    GenerationWorkspace workspace = invocation.getArgument(0);
                    String taskId = invocation.getArgument(2);
                    VueBuildResult result = builder.buildProjectWithResult(
                            workspace.frontendRootPath().toString(), taskId);
                    return result == null ? null : ProjectBuildValidationResult.fromVue(result);
                });
        return buildService(
                generationService,
                failureRecoveryService,
                devServerValidationService,
                projectBuildValidationService,
                appId);
    }

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            DevServerValidationService devServerValidationService,
            GenerationProjectBuildValidationService projectBuildValidationService,
            long appId
    ) {
        return buildService(
                generationService,
                failureRecoveryService,
                devServerValidationService,
                mock(GeneratedBackendRuntimeVerifier.class),
                projectBuildValidationService,
                appId);
    }

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            DevServerValidationService devServerValidationService,
            GeneratedBackendRuntimeVerifier backendRuntimeVerifier,
            GenerationProjectBuildValidationService projectBuildValidationService,
            long appId
    ) {
        GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier =
                mock(GeneratedFullStackRuntimeVerifier.class);
        when(fullStackRuntimeVerifier.verify(any(Path.class), any(), any()))
                .thenReturn(successfulFullStackRuntime());
        return buildService(
                generationService,
                failureRecoveryService,
                devServerValidationService,
                backendRuntimeVerifier,
                fullStackRuntimeVerifier,
                projectBuildValidationService,
                appId
        );
    }

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            DevServerValidationService devServerValidationService,
            GeneratedBackendRuntimeVerifier backendRuntimeVerifier,
            GeneratedFullStackRuntimeVerifier fullStackRuntimeVerifier,
            GenerationProjectBuildValidationService projectBuildValidationService,
            long appId
    ) {
        doReturn("repair prompt").when(generationService).buildAutoRepairPrompt(
                eq(appId), any(GenerationPreparation.class), any(Exception.class), anyInt());
        VueProjectValidationAdapter vueAdapter = new VueProjectValidationAdapter(
                mock(VueProjectBuilder.class),
                devServerValidationService);
        BackendProjectValidationAdapter backendAdapter = new BackendProjectValidationAdapter(
                mock(GoProjectBuilder.class),
                backendRuntimeVerifier);
        FullStackProjectValidationAdapter fullStackAdapter = new FullStackProjectValidationAdapter(
                vueAdapter,
                backendAdapter,
                mock(GenerationExecutionContextService.class),
                fullStackRuntimeVerifier);
        GenerationProjectRuntimeValidationService runtimeValidationService =
                new GenerationProjectRuntimeValidationService(
                        List.of(vueAdapter, backendAdapter, fullStackAdapter));
        return new HeavyGenerationBuildValidationService(
                mock(GenerationTaskLifecycleService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                new GenerationPerformanceMonitorService(),
                generationService,
                failureRecoveryService,
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                projectBuildValidationService,
                runtimeValidationService,
                stageAdmissionService(),
                mock(GenerationPreviewMilestoneService.class)
        );
    }

    private FullStackRuntimeValidationResult successfulFullStackRuntime() {
        BrowserRuntimeValidationResult browser = new BrowserRuntimeValidationResult(
                1, false, List.of(), List.of(), List.of(), Map.of());
        DevServerValidationResult frontend = DevServerValidationResult
                .passed("fullstack-test", 1L, 1)
                .withBrowserValidation(browser);
        return new FullStackRuntimeValidationResult(
                new BackendRuntimeValidationResult(
                        19_101, true, 1,
                        "go run -mod=readonly ./cmd/server", List.of()),
                frontend,
                2
        );
    }

    private GenerationStageAdmissionService stageAdmissionService() {
        return new GenerationStageAdmissionService(
                new GenerationStageAdmissionProperties(),
                new GenerationOrchestrationMetricsCollector(new SimpleMeterRegistry()),
                new GenerationPerformanceMonitorService()
        );
    }

    private GenerationExecutionContext executionContext(String taskId, long appId, int repairBudget) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, kind == GenerationBudgetKind.REPAIR_ROUND ? repairBudget : 5);
        }
        return new GenerationExecutionContext(
                taskId,
                appId,
                7L,
                Instant.now(),
                new GenerationExecutionLimits(
                        Duration.ofMinutes(5),
                        Duration.ofMinutes(2),
                        Duration.ofMillis(500),
                        budgets
                ),
                Clock.systemUTC()
        );
    }

    private GenerationPreparation preparation(String taskId) {
        return preparation(taskId, CodeGenTypeEnum.VUE_PROJECT);
    }

    private GenerationPreparation preparation(String taskId, CodeGenTypeEnum codeGenType) {
        return new GenerationPreparation(
                codeGenType,
                codeGenType,
                false,
                AppConstant.GENERATING_STAGE_UPDATE,
                "update project",
                List.of(),
                new java.util.HashMap<>(),
                null,
                Map.of(),
                taskId
        );
    }

    private Path projectPath(long appId) {
        return projectPath(appId, CodeGenTypeEnum.VUE_PROJECT);
    }

    private Path projectPath(long appId, CodeGenTypeEnum codeGenType) {
        Path projectRoot = projectRootPath(appId, codeGenType);
        return codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                ? projectRoot.resolve("frontend").normalize()
                : projectRoot;
    }

    private Path projectRootPath(long appId, CodeGenTypeEnum codeGenType) {
        return Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, codeGenType.getValue() + "_" + appId)
                .toAbsolutePath()
                .normalize();
    }

    private void createRepairableProject(Path projectPath) throws Exception {
        Files.createDirectories(projectPath.resolve("src"));
        Files.writeString(projectPath.resolve("package.json"), "{}");
        Files.writeString(projectPath.resolve("src/App.vue"), "<template><main /></template>");
    }
}
