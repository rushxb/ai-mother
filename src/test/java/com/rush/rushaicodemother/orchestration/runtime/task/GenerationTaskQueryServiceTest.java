package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskQueryServiceTest {

    private GenerationSessionRegistry registry;
    private GenerationExecutionContextService contextService;
    private GenerationTaskQueryService queryService;
    private DurableGenerationTaskRepository durableRepository;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private User owner;

    @BeforeEach
    void setUp() {
        registry = new GenerationSessionRegistry(new GenerationSessionProperties());
        contextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        durableRepository = mock(DurableGenerationTaskRepository.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        queryService = new GenerationTaskQueryService(
                registry, durableRepository, mock(GenerationTaskProgressEstimator.class));
        owner = user(2L);
    }

    @Test
    void queryMustExposeRouteDeadlineAndTaskWideBudgets() {
        register("task-query", owner);

        GenerationTaskSnapshot snapshot = queryService.get("task-query", owner);

        assertEquals("task-query", snapshot.taskId());
        assertEquals("lightweight_edit", snapshot.route());
        assertEquals("running", snapshot.status());
        assertTrue(snapshot.deadlineAt().isAfter(snapshot.submittedAt()));
        assertEquals(snapshot.limits().keySet(), snapshot.usages().keySet());
    }

    @Test
    void cancellationMustBeIdempotentAndVisibleAsCancellingUntilWorkerTerminates() {
        register("task-cancel", owner);
        GenerationTaskControlService controlService = new GenerationTaskControlService(
                queryService, durableRepository, runtimeLifecycleService, contextService);
        when(runtimeLifecycleService.requestCancellation("task-cancel", "user_requested")).thenReturn(true);

        GenerationTaskSnapshot first = controlService.cancel("task-cancel", owner);
        GenerationTaskSnapshot second = controlService.cancel("task-cancel", owner);

        assertEquals("cancelling", first.status());
        assertEquals("cancelling", second.status());
        assertTrue(first.cancellationRequested());
        verify(runtimeLifecycleService, org.mockito.Mockito.times(2))
                .requestCancellation("task-cancel", "user_requested");
    }

    @Test
    void taskLookupMustEnforceOwnership() {
        register("task-owned", owner);

        assertThrows(BusinessException.class, () -> queryService.get("task-owned", user(99L)));
    }

    @Test
    void terminalSnapshotMustRemainQueryableDuringReplayRetention() {
        GenerationSession session = register("task-terminal", owner);
        session.tryBeginCompletion();
        session.complete();
        registry.retainForReplay(1L, session);
        contextService.finish("task-terminal", "success");

        GenerationTaskSnapshot snapshot = queryService.get("task-terminal", owner);

        assertEquals("success", snapshot.status());
    }

    private GenerationSession register(String taskId, User actor) {
        App app = new App();
        app.setId(1L);
        app.setUserId(actor.getId());
        GenerationExecutionContext context = contextService.start(taskId, 1L, actor.getId());
        GenerationSession session = new GenerationSession(null, context);
        session.bindTaskRequest(new GenerationTaskRequest(app, "update title", actor));
        session.recordRoute("lightweight_edit");
        registry.put(1L, session);
        return session;
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
