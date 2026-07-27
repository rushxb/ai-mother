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
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HeavyGenerationSessionCompletionServiceTest {

    @Test
    void completedTaskMustNotBypassTheEnabledDurableOutbox() {
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationSemanticMemoryService semanticMemoryService = mock(GenerationSemanticMemoryService.class);
        MilvusMemoryProperties longTermProperties = new MilvusMemoryProperties();
        longTermProperties.setEnabled(true);
        GenerationMemoryOutboxProperties outboxProperties = new GenerationMemoryOutboxProperties();
        outboxProperties.setEnabled(true);
        GenerationOutcomeMemoryService outcomeMemoryService = new GenerationOutcomeMemoryService(
                semanticMemoryService, longTermProperties, outboxProperties);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                lifecycleService, runtimeLifecycleService, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.SUCCESS);

        verify(lifecycleService).completeGenerationAndCharge(
                eq("task-1"), eq(1L), eq(GenerationTaskStatus.SUCCESS), eq(null), anyString());
        verify(runtimeLifecycleService).completeUnowned("task-1", GenerationTaskStatus.SUCCESS, null);
        verifyNoInteractions(semanticMemoryService);
    }

    @Test
    void failedTaskMustPersistOutcomeWithoutCharging() {
        GenerationTaskLifecycleService lifecycleService = mock(GenerationTaskLifecycleService.class);
        GenerationTaskRuntimeLifecycleService runtimeLifecycleService =
                mock(GenerationTaskRuntimeLifecycleService.class);
        GenerationOutcomeMemoryService outcomeMemoryService = mock(GenerationOutcomeMemoryService.class);
        HeavyGenerationSessionCompletionService service = new HeavyGenerationSessionCompletionService(
                lifecycleService, runtimeLifecycleService, outcomeMemoryService);
        GenerationPreparation preparation = preparation();
        GenerationSession session = new GenerationSession(preparation);
        session.bindTaskRequest(new GenerationTaskRequest(app(), "创建订单管理页面", user()));

        service.completeClaimed(1L, session, preparation, GenerationTerminalOutcome.FAILED);

        verify(lifecycleService).completeGeneration(
                eq("task-1"), eq(1L), eq(GenerationTaskStatus.FAILED), eq("failed"), anyString());
        verify(lifecycleService, never()).completeGenerationAndCharge(
                eq("task-1"), eq(1L), eq(GenerationTaskStatus.FAILED),
                org.mockito.ArgumentMatchers.any(), anyString());
        verify(runtimeLifecycleService).completeUnowned(
                "task-1", GenerationTaskStatus.FAILED, "failed");
    }

    private GenerationPreparation preparation() {
        return new GenerationPreparation(
                CodeGenTypeEnum.HTML,
                CodeGenTypeEnum.VUE_PROJECT,
                true,
                "agent",
                "创建订单管理页面",
                List.of(),
                Map.of(),
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
