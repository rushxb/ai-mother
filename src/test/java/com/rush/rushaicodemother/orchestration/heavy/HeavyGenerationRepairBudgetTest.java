package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.builder.VueBuildResult;
import com.rush.rushaicodemother.core.builder.VueProjectBuilder;
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
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import com.rush.rushaicodemother.service.devserver.DevServerValidationResult;
import com.rush.rushaicodemother.service.devserver.DevServerValidationService;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.when;

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
                mock(StreamHandlerExecutor.class)
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

    private HeavyGenerationBuildValidationService buildService(
            HeavyGenerationExecutionService generationService,
            HeavyGenerationFailureRecoveryService failureRecoveryService,
            VueProjectBuilder builder,
            String taskId,
            long appId
    ) {
        DevServerValidationService devServerValidationService = mock(DevServerValidationService.class);
        doReturn("repair prompt").when(generationService).buildAutoRepairPrompt(
                eq(appId), any(GenerationPreparation.class), any(Exception.class), anyInt());
        when(devServerValidationService.validate(eq(taskId), eq(appId), eq(7L), any(CodeGenTypeEnum.class)))
                .thenReturn(DevServerValidationResult.passed(taskId, appId, 1));
        return new HeavyGenerationBuildValidationService(
                devServerValidationService,
                mock(GenerationTaskLifecycleService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                new GenerationPerformanceMonitorService(),
                generationService,
                failureRecoveryService,
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                builder
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
