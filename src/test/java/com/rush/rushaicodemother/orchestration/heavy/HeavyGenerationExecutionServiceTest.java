package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.CodeStorageProperties;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;
import com.rush.rushaicodemother.ai.model.GenerationPerformanceSelector;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.core.AiCodeGeneratorFacade;
import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.core.handler.StreamHandlerExecutor;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.plan.GenerationExecutionPlan;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationStageAdmissionService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.ChatHistoryService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class HeavyGenerationExecutionServiceTest {

    @Test
    void performanceProfileMustUsePlannerComplexityInsteadOfEnhancedPromptKeywords() {
        GenerationPerformanceSelector selector = mock(GenerationPerformanceSelector.class);
        GenerationPerformanceProfile fastProfile = GenerationPerformanceProfile.speedFirst();
        GenerationPerformanceProfile conservativeProfile = GenerationPerformanceProfile.qualityFirst();
        HeavyGenerationExecutionService service = spy(new HeavyGenerationExecutionService(
                mock(AiCodeGeneratorFacade.class),
                mock(ChatHistoryService.class),
                mock(GenerationAppStateService.class),
                mock(GenerationMemoryContextService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                selector,
                mock(HeavyGenerationFailureRecoveryService.class),
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                mock(StreamHandlerExecutor.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                mock(GenerationStageAdmissionService.class),
                new GenerationRuntimeProperties()
        ));
        String enhancedPrompt = "Vue 路由 API 管理系统模板规范";
        GenerationArtifact generationSpec = GenerationArtifact.of(
                "generation_spec", "Code", "生成规范", Map.of("requiresBuild", false));
        GenerationPreparation plannedSimple = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                enhancedPrompt,
                List.of(),
                Map.of(
                        "requirements", GenerationArtifact.of(
                                "requirements", "Planner", "需求与目标", Map.of("complex", false)),
                        "generation_spec", generationSpec
                ),
                null,
                Map.of(),
                "planned-simple"
        );
        GenerationPreparation legacyPreparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                "简单页面",
                List.of(),
                Map.of("generation_spec", generationSpec),
                null,
                Map.of(),
                "legacy-without-complexity"
        );
        GenerationSession plannedSession = spy(new GenerationSession(plannedSimple));
        GenerationSession legacySession = spy(new GenerationSession(legacyPreparation));
        User user = User.builder().id(7L).build();
        when(selector.select(true, false, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(fastProfile);
        when(selector.select(true, true, CodeGenTypeEnum.VUE_PROJECT)).thenReturn(conservativeProfile);
        doNothing().when(service).executeGenerationRound(
                any(), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), any(), any(),
                any(StringBuilder.class), any(long[].class), any(GenerationPerformanceProfile.class));

        service.runGenerationWithAutoRepair(41L, user, plannedSimple, plannedSession);
        service.runGenerationWithAutoRepair(42L, user, legacyPreparation, legacySession);

        verify(selector).select(true, false, CodeGenTypeEnum.VUE_PROJECT);
        verify(selector).select(true, true, CodeGenTypeEnum.VUE_PROJECT);
        ArgumentCaptor<GenerationStreamEvent> plannedEvent =
                ArgumentCaptor.forClass(GenerationStreamEvent.class);
        ArgumentCaptor<GenerationStreamEvent> legacyEvent =
                ArgumentCaptor.forClass(GenerationStreamEvent.class);
        verify(plannedSession).emit(plannedEvent.capture());
        verify(legacySession).emit(legacyEvent.capture());
        assertEquals(Boolean.FALSE, plannedEvent.getValue().getData().get("complexRequest"));
        assertEquals("planner_artifact", plannedEvent.getValue().getData().get("complexitySource"));
        assertEquals(Boolean.TRUE, legacyEvent.getValue().getData().get("complexRequest"));
        assertEquals("conservative_fallback", legacyEvent.getValue().getData().get("complexitySource"));
    }

    @Test
    void frozenExecutionPlanMustProvideInitialModelProfileWithoutRuntimeReselection() {
        GenerationPerformanceSelector selector = mock(GenerationPerformanceSelector.class);
        GenerationPerformanceProfile plannedProfile = GenerationPerformanceProfile.qualityFirst();
        HeavyGenerationExecutionService service = spy(new HeavyGenerationExecutionService(
                mock(AiCodeGeneratorFacade.class),
                mock(ChatHistoryService.class),
                mock(GenerationAppStateService.class),
                mock(GenerationMemoryContextService.class),
                mock(GenerationOrchestrationMetricsCollector.class),
                selector,
                mock(HeavyGenerationFailureRecoveryService.class),
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                mock(StreamHandlerExecutor.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                mock(GenerationStageAdmissionService.class),
                new GenerationRuntimeProperties()
        ));
        GenerationArtifact generationSpec = GenerationArtifact.of(
                "generation_spec", "Code", "生成规范", Map.of("requiresBuild", false));
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                AppConstant.GENERATING_STAGE_CREATE,
                "生成管理页面",
                List.of(),
                Map.of("generation_spec", generationSpec),
                null,
                Map.of(),
                "planned-profile"
        );
        GenerationSession session = new GenerationSession(preparation);
        GenerationExecutionPlan plan = mock(GenerationExecutionPlan.class);
        when(plan.modelProfile()).thenReturn(plannedProfile);
        session.bindExecutionPlan(plan);
        User user = User.builder().id(7L).build();
        doNothing().when(service).executeGenerationRound(
                any(), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), any(), same(session),
                any(StringBuilder.class), any(long[].class), same(plannedProfile));

        service.runGenerationWithAutoRepair(41L, user, preparation, session);

        verifyNoInteractions(selector);
        verify(service).executeGenerationRound(
                eq(41L), same(user), eq(CodeGenTypeEnum.VUE_PROJECT), eq("生成管理页面"), same(session),
                any(StringBuilder.class), any(long[].class), same(plannedProfile));
    }

    @Test
    void autoRepairPromptMustPreserveSanitizedDiagnosticForMemoryAndModel() {
        GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        HeavyGenerationExecutionService service = new HeavyGenerationExecutionService(
                mock(AiCodeGeneratorFacade.class),
                mock(ChatHistoryService.class),
                mock(GenerationAppStateService.class),
                memoryContextService,
                mock(GenerationOrchestrationMetricsCollector.class),
                mock(GenerationPerformanceSelector.class),
                failureRecoveryService,
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                mock(StreamHandlerExecutor.class),
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                mock(GenerationStageAdmissionService.class),
                new GenerationRuntimeProperties()
        );
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.VUE_PROJECT,
                CodeGenTypeEnum.VUE_PROJECT,
                false,
                "create",
                "build a page",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-43"
        );
        RuntimeException failure = new RuntimeException("provider-api-key=secret-value");
        GenerationErrorClassifier.GenerationError classifiedFailure = GenerationErrorClassifier.classify(failure);
        when(failureRecoveryService.classifyGenerationError(failure)).thenReturn(classifiedFailure);
        String sanitizedDiagnostic = "provider-api-key=[REDACTED]";
        when(memoryContextService.buildAutoRepairMemoryContext(
                43L,
                "task-43",
                sanitizedDiagnostic,
                1
        )).thenReturn("");

        String prompt = service.buildAutoRepairPrompt(43L, preparation, failure, 1);

        assertTrue(prompt.contains(classifiedFailure.message()));
        assertTrue(prompt.contains(sanitizedDiagnostic));
        assertTrue(prompt.contains("BEGIN_UNTRUSTED_VALIDATION_DIAGNOSTIC"));
        assertTrue(prompt.contains("编排器会统一执行构建与运行时复验"));
        assertFalse(prompt.contains("必须先调用本地构建诊断工具"));
        assertFalse(prompt.contains("secret-value"));
        verify(memoryContextService).buildAutoRepairMemoryContext(
                43L,
                "task-43",
                sanitizedDiagnostic,
                1
        );
    }

    @Test
    void exhaustedGenerationFailureMustHideInternalDetailsAndPreserveCause() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        GenerationAppStateService appStateService = mock(GenerationAppStateService.class);
        GenerationMemoryContextService memoryContextService = mock(GenerationMemoryContextService.class);
        GenerationOrchestrationMetricsCollector metricsCollector = mock(GenerationOrchestrationMetricsCollector.class);
        GenerationPerformanceSelector performanceSelector = mock(GenerationPerformanceSelector.class);
        HeavyGenerationFailureRecoveryService failureRecoveryService = mock(HeavyGenerationFailureRecoveryService.class);
        HeavyGenerationSessionCompletionService completionService = mock(HeavyGenerationSessionCompletionService.class);
        StreamHandlerExecutor streamHandlerExecutor = mock(StreamHandlerExecutor.class);
        HeavyGenerationExecutionService service = spy(new HeavyGenerationExecutionService(
                facade,
                chatHistoryService,
                appStateService,
                memoryContextService,
                metricsCollector,
                performanceSelector,
                failureRecoveryService,
                completionService,
                new GenerationWorkspaceService(new CodeStorageProperties()),
                streamHandlerExecutor,
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                mock(GenerationStageAdmissionService.class),
                new GenerationRuntimeProperties()
        ));
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.HTML,
                false,
                "create",
                "build a page",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-42"
        );
        GenerationSession session = new GenerationSession(preparation);
        User user = User.builder().id(7L).build();
        GenerationPerformanceProfile profile = GenerationPerformanceProfile.speedFirst();
        RuntimeException failure = new RuntimeException("provider-api-key=secret-value");
        when(performanceSelector.select(anyBoolean(), anyBoolean(), eq(CodeGenTypeEnum.HTML)))
                .thenReturn(profile);
        when(failureRecoveryService.classifyGenerationError(failure)).thenReturn(
                new GenerationErrorClassifier.GenerationError(
                        GenerationErrorClassifier.CATEGORY_RUNTIME,
                        failure.getMessage(),
                        false
                )
        );
        doThrow(failure).when(service).executeGenerationRound(
                eq(42L),
                same(user),
                eq(CodeGenTypeEnum.HTML),
                eq("build a page"),
                same(session),
                any(StringBuilder.class),
                any(long[].class),
                same(profile)
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.runGenerationWithAutoRepair(42L, user, preparation, session)
        );

        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), exception.getCode());
        assertEquals("代码生成失败，请稍后重试", exception.getMessage());
        assertFalse(exception.getMessage().contains("secret-value"));
        assertSame(failure, exception.getCause());
    }

    @Test
    void firstEventMustSkipSnapshotAndDueEventMustEmitBeforePersistence() {
        AiCodeGeneratorFacade facade = mock(AiCodeGeneratorFacade.class);
        ChatHistoryService chatHistoryService = mock(ChatHistoryService.class);
        GenerationAppStateService appStateService = mock(GenerationAppStateService.class);
        GenerationOrchestrationMetricsCollector metricsCollector =
                mock(GenerationOrchestrationMetricsCollector.class);
        StreamHandlerExecutor streamHandlerExecutor = mock(StreamHandlerExecutor.class);
        HeavyGenerationExecutionService service = new HeavyGenerationExecutionService(
                facade,
                chatHistoryService,
                appStateService,
                mock(GenerationMemoryContextService.class),
                metricsCollector,
                mock(GenerationPerformanceSelector.class),
                mock(HeavyGenerationFailureRecoveryService.class),
                mock(HeavyGenerationSessionCompletionService.class),
                new GenerationWorkspaceService(new CodeStorageProperties()),
                streamHandlerExecutor,
                mock(com.rush.rushaicodemother.orchestration.runtime.agent.GenerationAgentRuntime.class),
                mock(GenerationStageAdmissionService.class),
                new GenerationRuntimeProperties()
        );
        GenerationPreparation preparation = new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.HTML,
                false,
                "create",
                "build a page",
                List.of(),
                Map.of(),
                null,
                Map.of(),
                "task-stream-order"
        );
        GenerationSession session = spy(new GenerationSession(preparation));
        User user = User.builder().id(7L).build();
        GenerationStreamEvent event = GenerationStreamEvent.aiDelta("first token");
        Flux<GenerationStreamEvent> codeStream = Flux.just(event);
        when(facade.generateAndSaveCodeStream(
                eq("build a page"),
                eq(CodeGenTypeEnum.HTML),
                eq(42L),
                any(),
                any(),
                any(),
                any()
        )).thenReturn(codeStream);
        when(streamHandlerExecutor.doExecute(
                same(codeStream),
                same(chatHistoryService),
                eq(42L),
                same(user),
                eq(CodeGenTypeEnum.HTML)
        )).thenReturn(codeStream);

        long[] lastSnapshotUpdateAt = {0L};
        service.executeGenerationRound(
                42L,
                user,
                CodeGenTypeEnum.HTML,
                "build a page",
                session,
                new StringBuilder(),
                lastSnapshotUpdateAt
        );
        verify(appStateService, never()).updateOwnedGenerationSnapshot(
                any(), any(), any());

        lastSnapshotUpdateAt[0] = System.currentTimeMillis() - Duration.ofSeconds(6).toMillis();
        service.executeGenerationRound(
                42L,
                user,
                CodeGenTypeEnum.HTML,
                "build a page",
                session,
                new StringBuilder(),
                lastSnapshotUpdateAt
        );

        InOrder order = inOrder(session, appStateService);
        order.verify(session, times(2)).emit(same(event));
        order.verify(appStateService).updateOwnedGenerationSnapshot(
                42L, "task-stream-order", "first token");
        verify(metricsCollector).recordStreamSnapshotWrite(eq("persisted"), any(Duration.class));
    }
}
