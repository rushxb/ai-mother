package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.config.GenerationMemoryOutboxProperties;
import com.rush.rushaicodemother.config.MilvusMemoryProperties;
import com.rush.rushaicodemother.memory.GenerationOutcomeMemoryService;
import com.rush.rushaicodemother.memory.GenerationSemanticMemoryService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.GenerationTerminalOutcome;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.artifact.PatchResult;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTerminalIntentService;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationBudgetKind;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionLimits;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HeavyGenerationSessionCompletionServiceTest {

    @Test
    void publishedSuccessWithoutPreparedTerminalIntentMustFailClosed() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        DurableGenerationTaskRepository repository = mock(DurableGenerationTaskRepository.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer,
                mock(GenerationOutcomeMemoryService.class),
                new GenerationTerminalIntentService(repository));
        GenerationPreparation preparation = preparation();
        GenerationSession session = managedSession(preparation);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.completeClaimed(
                        1L, session, preparation, GenerationTerminalOutcome.SUCCESS));

        assertEquals("已发布任务缺少可恢复终态意图", failure.getMessage());
        verify(finalizer, never()).finalizeManaged(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void publishedSuccessMustFinalizeTheFrozenTerminalIntent() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationTerminalIntentService terminalIntentService = mock(GenerationTerminalIntentService.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, mock(GenerationOutcomeMemoryService.class), terminalIntentService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = managedSession(preparation);
        GenerationFinalizationCommand frozenIntent = GenerationFinalizationCommand.of(
                preparation.taskId(),
                1L,
                session.executionContext().executionFence(),
                GenerationTaskStatus.SUCCESS,
                null,
                "发布前冻结的完整记忆",
                null);
        when(terminalIntentService.requirePrepared(org.mockito.ArgumentMatchers.any()))
                .thenReturn(frozenIntent);

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(finalizer).finalizeManaged(frozenIntent);
    }

    @Test
    void completedTaskMustNotBypassTheEnabledDurableOutbox() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        MilvusMemoryProperties longTermProperties = new MilvusMemoryProperties();
        longTermProperties.setEnabled(true);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        outboxProperties.setEnabled(true);
        GenerationOutcomeMemoryService outcomeMemoryService = new GenerationOutcomeMemoryService(
                semanticMemoryService, longTermProperties, outboxProperties);
        HeavyGenerationSessionCompletionService service = service(finalizer, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-1")
                        && command.appId().equals(1L)
                        && command.status() == GenerationTaskStatus.SUCCESS
                        && command.reason() == null
                        && command.deliveryReceipt() != null
                        && "heavy_generation".equals(command.deliveryReceipt().actualRoute())
                        && command.memorySummary() != null));
        verifyNoInteractions(semanticMemoryService);
    }

    @Test
    void failedTaskMustPersistOutcomeWithoutCharging() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        GenerationTerminalIntentService terminalIntentService = mock(GenerationTerminalIntentService.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService, terminalIntentService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.FAILED);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-1")
                        && command.appId().equals(1L)
                        && command.status() == GenerationTaskStatus.FAILED
                        && command.reason().equals("failed")
                        && command.deliveryReceipt() != null
                        && "unknown".equals(command.deliveryReceipt().failureCategory())
                        && command.memorySummary() != null));
        verifyNoInteractions(terminalIntentService);
    }

    @Test
    void invalidChangePlanMustNotBlockFailedTaskFinalization() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        HeavyGenerationSessionCompletionService service = service(
                finalizer, mock(GenerationOutcomeMemoryService.class));
        GenerationPreparation preparation = preparation();
        preparation.putArtifact(GenerationArtifact.of(
                ChangePlan.KEY,
                "test",
                "损坏的变更计划",
                Map.of("schemaVersion", "v1")
        ));
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        assertDoesNotThrow(() -> service.completeClaimed(
                1L, session, preparation, GenerationTerminalOutcome.FAILED));

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.status() == GenerationTaskStatus.FAILED
                        && command.memorySummary().contains("变更计划：制品无效，未纳入结果记忆")));
    }

    @Test
    void memoryFailureMustNotTurnCommittedTerminalStateIntoFailure() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        HeavyGenerationSessionCompletionService service = service(finalizer, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));
        doThrow(new IllegalStateException("记忆服务不可用"))
                .when(outcomeMemoryService).remember(org.mockito.ArgumentMatchers.any());

        assertDoesNotThrow(() -> service.completeClaimed(
                1L, session, preparation, GenerationTerminalOutcome.SUCCESS));

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.any());
        verify(outcomeMemoryService).remember(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void skippedDiffWithStaleCountMustNotPolluteOutcomeQuality() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        HeavyGenerationSessionCompletionService service = service(finalizer, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        Map<String, Object> stalePayload = new LinkedHashMap<>(DiffSummary.skipped(
                1L,
                "task-1",
                "D:/workspace/base",
                "D:/workspace/current",
                "snapshot_unavailable"
        ).toPayload());
        stalePayload.put("addedCount", 1);
        stalePayload.put("addedFiles", List.of("src/App.vue"));
        preparation.putArtifact(GenerationArtifact.of(
                DiffSummary.KEY, "test", "损坏的差异摘要", stalePayload));
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.outcomeQuality() != null
                        && command.outcomeQuality().changedFileCount() == null
                        && !command.memorySummary().contains("src/App.vue")));
    }

    @Test
    void foreignPatchResultMustNotPolluteOutcomeMemory() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        HeavyGenerationSessionCompletionService service = service(
                finalizer, mock(GenerationOutcomeMemoryService.class));
        GenerationPreparation preparation = preparation();
        preparation.putArtifact(new PatchResult(
                "v1",
                "local_diff",
                "applied",
                99L,
                "foreign-task",
                List.of("src/Foreign.vue"),
                List.of(),
                List.of(),
                List.of("src/Foreign.vue"),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                1,
                0,
                0,
                "",
                null
        ).toArtifact());
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.memorySummary().contains("Patch 结果：制品无效，未纳入结果记忆")
                        && !command.memorySummary().contains("src/Foreign.vue")));
    }

    private GenerationPreparation preparation() {
        return new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.VUE_PROJECT,
                true,
                "agent",
                "创建订单管理页面",
                List.of(),
                new LinkedHashMap<>(),
                null,
                Map.of(),
                "task-1"
        );
    }

    private HeavyGenerationSessionCompletionService service(
            GenerationTaskFinalizer finalizer,
            GenerationOutcomeMemoryService outcomeMemoryService) {
        GenerationTerminalIntentService terminalIntentService = mock(GenerationTerminalIntentService.class);
        when(terminalIntentService.requirePrepared(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService, terminalIntentService);
    }

    private GenerationSession managedSession(GenerationPreparation preparation) {
        EnumMap<GenerationBudgetKind, Integer> budgets = new EnumMap<>(GenerationBudgetKind.class);
        for (GenerationBudgetKind kind : GenerationBudgetKind.values()) {
            budgets.put(kind, 2);
        }
        Instant startedAt = Instant.parse("2026-08-19T00:00:00Z");
        GenerationExecutionContext context = new GenerationExecutionContext(
                preparation.taskId(),
                1L,
                2L,
                startedAt,
                new GenerationExecutionLimits(
                        Duration.ofMinutes(10), Duration.ofMinutes(2), Duration.ofMillis(500), budgets),
                Clock.fixed(startedAt.plusSeconds(30), ZoneOffset.UTC));
        context.bindExecutionFence(new GenerationExecutionFence(
                preparation.taskId(), "worker-1", 1L));
        GenerationSession session = new GenerationSession(preparation, context);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));
        return session;
    }

    private App app() {
        App app = new App();
        app.setId(1L);
        app.setTenantId(9L);
        return app;
    }

    private User user() {
        User user = new User();
        user.setId(2L);
        return user;
    }
}
