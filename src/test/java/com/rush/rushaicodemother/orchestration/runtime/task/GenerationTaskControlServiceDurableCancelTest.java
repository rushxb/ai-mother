package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationTaskControlServiceDurableCancelTest {

    private static final Instant NOW = Instant.parse("2026-07-16T07:00:00Z");

    private DurableGenerationTaskRepository repository;
    private GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private GenerationExecutionContextService executionContextService;
    private GenerationTaskControlService service;
    private TenantAuthorizationService tenantAuthorizationService;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        runtimeLifecycleService = mock(GenerationTaskRuntimeLifecycleService.class);
        tenantAuthorizationService = mock(TenantAuthorizationService.class);
        executionContextService = new GenerationExecutionContextService(new GenerationRuntimeProperties());
        GenerationTaskQueryService queryService = new GenerationTaskQueryService(
                new GenerationSessionRegistry(new GenerationSessionProperties()), repository,
                mock(GenerationTaskProgressEstimator.class), mock(GenerationEventStream.class),
                mock(AppPersistenceService.class), tenantAuthorizationService);
        service = new GenerationTaskControlService(
                queryService, repository, runtimeLifecycleService, executionContextService,
                tenantAuthorizationService);
    }

    @Test
    void taskScopedCancellationMustWorkWithoutProcessLocalSession() {
        when(repository.findByTaskId("task-remote"))
                .thenReturn(Optional.of(record(false)), Optional.of(record(true)));
        when(runtimeLifecycleService.requestCancellation("task-remote", "user_requested"))
                .thenReturn(true);

        GenerationTaskSnapshot snapshot = service.cancel("task-remote", user(2L));

        assertTrue(snapshot.cancellationRequested());
        assertEquals("user_requested", snapshot.cancellationReason());
        verify(runtimeLifecycleService).requestCancellation("task-remote", "user_requested");
    }

    @Test
    void legacyAppScopedStopMustResolveActiveTaskFromDurableRepository() {
        when(repository.findLatestNonTerminalByAppId(1L)).thenReturn(Optional.of(record(false)));
        when(repository.findByTaskId("task-remote")).thenReturn(Optional.of(record(true)));

        GenerationTaskSnapshot snapshot = service.cancelActiveForApp(1L, user(2L));

        assertEquals("task-remote", snapshot.taskId());
        assertTrue(snapshot.cancellationRequested());
        verify(runtimeLifecycleService).requestCancellation("task-remote", "user_requested");
    }

    @Test
    void waitingApprovalCancellationMustImmediatelyBecomeTerminal() {
        DurableGenerationTaskRecord waiting = recordWithStatus(
                GenerationTaskStatus.WAITING_APPROVAL, false);
        DurableGenerationTaskRecord cancelled = recordWithStatus(
                GenerationTaskStatus.CANCELLED, true);
        when(repository.findByTaskId("task-remote"))
                .thenReturn(Optional.of(waiting), Optional.of(waiting),
                        Optional.of(waiting), Optional.of(cancelled));
        when(runtimeLifecycleService.requestCancellation("task-remote", "user_requested"))
                .thenReturn(true);

        GenerationTaskSnapshot snapshot = service.cancel("task-remote", user(2L));

        assertEquals("cancelled", snapshot.status());
        verify(runtimeLifecycleService).completeUnowned(
                "task-remote", GenerationTaskStatus.CANCELLED, "user_requested");
    }

    private DurableGenerationTaskRecord record(boolean cancellationRequested) {
        return new DurableGenerationTaskRecord(
                "task-remote", 1L, 2L, 100L, "agent_edit", GenerationTaskStatus.RUNNING, "agent", "正在执行",
                NOW, NOW.plusSeconds(1_200), cancellationRequested,
                cancellationRequested ? "user_requested" : null,
                "worker-b", NOW.plusSeconds(30), NOW, 1, 3L, null, null);
    }

    private DurableGenerationTaskRecord recordWithStatus(GenerationTaskStatus status,
                                                         boolean cancellationRequested) {
        boolean unowned = status == GenerationTaskStatus.WAITING_APPROVAL || status.isTerminal();
        return new DurableGenerationTaskRecord(
                "task-remote", 1L, 2L, 100L, "agent_edit", status, "approval", "approval required",
                NOW, NOW.plusSeconds(1_200), cancellationRequested,
                cancellationRequested ? "user_requested" : null,
                unowned ? null : "worker-b", unowned ? null : NOW.plusSeconds(30),
                unowned ? null : NOW, 1, 3L, status.isTerminal() ? NOW : null, null);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
