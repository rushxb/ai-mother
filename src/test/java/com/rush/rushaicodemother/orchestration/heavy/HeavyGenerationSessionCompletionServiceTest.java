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
import com.rush.rushaicodemother.orchestration.artifact.DiffSummary;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.finalization.GenerationTaskFinalizer;
import org.junit.jupiter.api.Test;

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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HeavyGenerationSessionCompletionServiceTest {

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
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-1")
                        && command.appId().equals(1L)
                        && command.status() == GenerationTaskStatus.SUCCESS
                        && command.reason() == null
                        && command.memorySummary() != null));
        verifyNoInteractions(semanticMemoryService);
    }

    @Test
    void failedTaskMustPersistOutcomeWithoutCharging() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.FAILED);

        verify(finalizer).finalizeManaged(org.mockito.ArgumentMatchers.argThat(command ->
                command.taskId().equals("task-1")
                        && command.appId().equals(1L)
                        && command.status() == GenerationTaskStatus.FAILED
                        && command.reason().equals("failed")
                        && command.memorySummary() != null));
    }

    @Test
    void memoryFailureMustNotTurnCommittedTerminalStateIntoFailure() {
        GenerationTaskFinalizer finalizer = mock(GenerationTaskFinalizer.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService);
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
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                finalizer, outcomeMemoryService);
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
