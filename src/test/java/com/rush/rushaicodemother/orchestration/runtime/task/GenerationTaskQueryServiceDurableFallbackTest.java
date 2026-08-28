package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.attempt.completion.GenerationCompletionEvidenceSet;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceipt;
import com.rush.rushaicodemother.orchestration.delivery.GenerationDeliveryReceiptFactory;
import com.rush.rushaicodemother.orchestration.delivery.GenerationCostSummary;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContext;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.credit.GenerationTaskCostProjectionService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
import com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class GenerationTaskQueryServiceDurableFallbackTest {

    private static final Instant SUBMITTED_AT = Instant.parse("2026-07-16T06:00:00Z");

    private DurableGenerationTaskRepository repository;
    private GenerationTaskProgressEstimator progressEstimator;
    private GenerationTaskQueryService service;
    private GenerationEventStream eventStream;
    private AppPersistenceService appPersistenceService;
    private TenantAuthorizationService tenantAuthorizationService;
    private GenerationSessionRegistry sessionRegistry;
    private GenerationTaskCostProjectionService costProjectionService;

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        progressEstimator = mock(GenerationTaskProgressEstimator.class);
        eventStream = mock(GenerationEventStream.class);
        appPersistenceService = mock(AppPersistenceService.class);
        tenantAuthorizationService = mock(TenantAuthorizationService.class);
        sessionRegistry = mock(GenerationSessionRegistry.class);
        costProjectionService = mock(GenerationTaskCostProjectionService.class);
        service = new GenerationTaskQueryService(
                sessionRegistry, repository,
                progressEstimator, eventStream, appPersistenceService,
                tenantAuthorizationService, costProjectionService);
    }

    @Test
    void missingLocalSessionMustFallBackToDurableStatusAfterRestart() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        GenerationTaskProgressEstimate estimate = GenerationTaskProgressEstimate.unavailable(0L, SUBMITTED_AT);
        when(progressEstimator.estimate(
                "heavy_generation", "queued", SUBMITTED_AT, SUBMITTED_AT.plusSeconds(1_200), "queued"))
                .thenReturn(estimate);

        GenerationTaskSnapshot snapshot = service.get("task-durable", user(2L));

        assertEquals("queued", snapshot.status());
        assertEquals("heavy_generation", snapshot.route());
        assertEquals(SUBMITTED_AT, snapshot.submittedAt());
        assertEquals("queued", snapshot.stage());
        assertEquals("等待执行", snapshot.stageMessage());
        assertEquals(estimate, snapshot.progress());
        assertTrue(snapshot.usages().isEmpty());
        assertTrue(snapshot.limits().isEmpty());
    }

    @Test
    void taskStatusMustPreferDurableTerminalOverStaleActiveSession() {
        GenerationSession staleSession = mock(GenerationSession.class);
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        Instant deadlineAt = SUBMITTED_AT.plusSeconds(1_200);
        GenerationExecutionSnapshot staleExecution = new GenerationExecutionSnapshot(
                "task-durable", 1L, 2L, SUBMITTED_AT, deadlineAt,
                "default", deadlineAt, null, false, null, null,
                java.util.Map.of(), java.util.Map.of());
        when(staleSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(staleSession.isActive()).thenReturn(true);
        when(staleSession.executionContext()).thenReturn(executionContext);
        when(staleSession.route()).thenReturn("heavy_generation");
        when(executionContext.snapshot()).thenReturn(staleExecution);
        when(sessionRegistry.getByTaskId("task-durable")).thenReturn(staleSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        GenerationTaskSnapshot snapshot = service.get("task-durable", user(2L));

        assertEquals("success", snapshot.status());
    }

    @Test
    void taskStatusMustUseDurableProgressWhenRetainedLocalSessionIsInactive() {
        GenerationSession retainedSession = mock(GenerationSession.class);
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        Instant deadlineAt = SUBMITTED_AT.plusSeconds(1_200);
        GenerationExecutionSnapshot staleExecution = new GenerationExecutionSnapshot(
                "task-durable", 1L, 2L, SUBMITTED_AT, deadlineAt,
                "default", deadlineAt, null, false, null, null,
                java.util.Map.of(), java.util.Map.of());
        when(retainedSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(retainedSession.isActive()).thenReturn(false);
        when(retainedSession.executionContext()).thenReturn(executionContext);
        when(retainedSession.route()).thenReturn("heavy_generation");
        when(executionContext.snapshot()).thenReturn(staleExecution);
        when(sessionRegistry.getByTaskId("task-durable")).thenReturn(retainedSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));

        GenerationTaskSnapshot snapshot = service.get("task-durable", user(2L));

        assertEquals("queued", snapshot.status());
        assertEquals("等待执行", snapshot.stageMessage());
    }

    @Test
    void latestNonTerminalAppTaskMustSupportRefreshAndCrossClientResume() {
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        when(appPersistenceService.findActiveById(1L)).thenReturn(app);
        when(repository.findLatestNonTerminalByAppId(1L)).thenReturn(Optional.of(record(2L)));

        Optional<GenerationTaskSnapshot> snapshot = service.findLatestNonTerminalForApp(1L, user(2L));

        assertTrue(snapshot.isPresent());
        assertEquals("task-durable", snapshot.orElseThrow().taskId());
        assertEquals("queued", snapshot.orElseThrow().status());
    }

    @Test
    void activeTaskLookupMustNotExposeStaleSessionAfterDurableTerminal() {
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        GenerationSession staleSession = mock(GenerationSession.class);
        GenerationExecutionContext executionContext = mock(GenerationExecutionContext.class);
        Instant deadlineAt = SUBMITTED_AT.plusSeconds(1_200);
        GenerationExecutionSnapshot staleExecution = new GenerationExecutionSnapshot(
                "task-durable", 1L, 2L, SUBMITTED_AT, deadlineAt,
                "default", deadlineAt, null, false, null, null,
                java.util.Map.of(), java.util.Map.of());
        when(appPersistenceService.findActiveById(1L)).thenReturn(app);
        when(staleSession.taskId()).thenReturn("task-durable");
        when(staleSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(staleSession.isActive()).thenReturn(true);
        when(staleSession.executionContext()).thenReturn(executionContext);
        when(staleSession.route()).thenReturn("heavy_generation");
        when(executionContext.snapshot()).thenReturn(staleExecution);
        when(sessionRegistry.get(1L)).thenReturn(staleSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        Optional<GenerationTaskSnapshot> snapshot = service
                .findLatestNonTerminalForApp(1L, user(2L));

        assertTrue(snapshot.isEmpty());
    }

    @Test
    void durableFallbackMustStillEnforceOwnershipForStatusAndEvents() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        doThrow(new BusinessException(com.rush.rushaicodemother.exception.ErrorCode.NO_AUTH_ERROR, "denied"))
                .when(tenantAuthorizationService)
                .requireRole(eq(100L), eq(99L), eq(TenantRole.VIEWER), anyString());

        assertThrows(BusinessException.class, () -> service.get("task-durable", user(99L)));
        assertThrows(BusinessException.class, () -> service.events("task-durable", user(99L)));
    }

    @Test
    void nonTerminalEventsMustAllowSubscriptionBeforeSharedEventLogExists() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        when(eventStream.stream("task-durable"))
                .thenReturn(Flux.just(GenerationStreamEvent.aiDelta("queued")));

        List<GenerationStreamEvent> events = service.events("task-durable", user(2L))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals("queued", events.getFirst().getText());
    }

    @Test
    void terminalEventsMustBeSynthesizedFromDurableTruthAfterReplayRetentionExpires() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        List<GenerationStreamEvent> events = service.events("task-durable", user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals(GenerationStreamEvent.TASK_TERMINAL, events.getFirst().getType());
        assertEquals("success", events.getFirst().getData().get("status"));
        assertEquals("task-durable:success:durable-terminal",
                events.getFirst().getData().get("eventId"));
    }

    @Test
    void statusAndDurableTerminalEventMustExposeTheSameDeliveryReceipt() {
        GenerationDeliveryReceipt receipt = GenerationDeliveryReceiptFactory.fromTerminal(
                "heavy_generation", GenerationTaskStatus.FAILED,
                GenerationCompletionEvidenceSet.empty(),
                GenerationOutcomeQuality.ofFailure("model_timeout", 2, 1, 900L));
        DurableGenerationTaskRecord record = new DurableGenerationTaskRecord(
                "task-durable", 1L, 2L, 100L, "heavy_generation",
                GenerationTaskStatus.FAILED, "completed", null,
                SUBMITTED_AT, SUBMITTED_AT.plusSeconds(1_200), false, null,
                null, null, SUBMITTED_AT, 3L, 1, 4L,
                SUBMITTED_AT.plusSeconds(30), "internal failure", receipt);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record));
        GenerationCostSummary costSummary = new GenerationCostSummary(
                "settled", 120_000L, 2L, true,
                5L, 140_000L, null, 3L, "actual_cost_below_reserved",
                20_000L, "provider_timeout", "实际扣费 2 积分，已退还 3 积分");
        when(costProjectionService.project("task-durable", true)).thenReturn(costSummary);

        GenerationTaskSnapshot snapshot = service.get("task-durable", user(2L));
        GenerationStreamEvent event = service.events("task-durable", user(2L))
                .blockFirst(Duration.ofSeconds(1));

        assertEquals(receipt.withCostSummary(costSummary), snapshot.deliveryReceipt());
        assertEquals("heavy_generation", ((java.util.Map<?, ?>)
                event.getData().get("deliveryReceipt")).get("actualRoute"));
        assertEquals("model_timeout", event.getData().get("failureCategory"));
        assertEquals("retry", event.getData().get("recoveryAction"));
        java.util.Map<?, ?> eventCost = (java.util.Map<?, ?>) event.getData().get("costSummary");
        assertEquals(3L, eventCost.get("refundedCredit"));
        assertEquals("provider_timeout", eventCost.get("waiverReason"));
    }

    @Test
    void sequencedTerminalFallbackMustEmitOneStableEventAndCloseTheStream() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.FAILED)));

        List<SequencedGenerationEvent> events = service
                .sequencedEvents("task-durable", 41L, user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(List.of(
                        DurableGenerationTerminalEventProjection.TERMINAL_SEQUENCE,
                        DurableGenerationTerminalEventProjection.COMPLETE_SEQUENCE),
                events.stream().map(SequencedGenerationEvent::sequence).toList());
        assertEquals(SequencedGenerationEvent.Kind.EVENT, events.getFirst().kind());
        assertEquals("failed", events.getFirst().event().getData().get("status"));
        assertTrue(events.getLast().terminal());
    }

    @Test
    void durableTerminalMustOverrideIncompleteSharedReplay() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));
        when(eventStream.available("task-durable")).thenReturn(true);
        when(eventStream.stream("task-durable", 0L)).thenReturn(Flux.just(
                SequencedGenerationEvent.event(1L, GenerationStreamEvent.aiDelta("partial"))));

        List<SequencedGenerationEvent> events = service
                .sequencedEvents("task-durable", 0L, user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(GenerationStreamEvent.TASK_TERMINAL,
                events.getFirst().event().getType());
        assertTrue(events.getLast().terminal());
        verifyNoInteractions(eventStream);
    }

    @Test
    void retainedInactiveSessionMustNotMaskDurableTerminalFallback() {
        GenerationSession retainedSession = mock(GenerationSession.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        when(retainedSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(retainedSession.isActive()).thenReturn(false);
        when(sessionRegistry.getByTaskId("task-durable")).thenReturn(retainedSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        List<SequencedGenerationEvent> events = service
                .sequencedEvents("task-durable", 0L, user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(GenerationStreamEvent.TASK_TERMINAL,
                events.getFirst().event().getType());
        assertTrue(events.getLast().terminal());
    }

    @Test
    void staleActiveSessionMustNotOverrideDurableTerminalTruth() {
        GenerationSession staleSession = mock(GenerationSession.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        when(staleSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(staleSession.isActive()).thenReturn(true);
        when(staleSession.asFlux()).thenReturn(Flux.just(GenerationStreamEvent.aiDelta("stale")));
        when(sessionRegistry.getByTaskId("task-durable")).thenReturn(staleSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        List<GenerationStreamEvent> events = service.events("task-durable", user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(GenerationStreamEvent.TASK_TERMINAL, events.getFirst().getType());
    }

    @Test
    void appScopedEventsMustPreferDurableTerminalOverStaleActiveSession() {
        GenerationSession staleSession = mock(GenerationSession.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        when(staleSession.taskId()).thenReturn("task-durable");
        when(staleSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(staleSession.isActive()).thenReturn(true);
        when(staleSession.asFlux()).thenReturn(
                Flux.just(GenerationStreamEvent.aiDelta("stale")));
        when(sessionRegistry.get(1L)).thenReturn(staleSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        List<GenerationStreamEvent> events = service
                .eventsForLatestNonTerminalAppTask(1L, user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals(GenerationStreamEvent.TASK_TERMINAL,
                events.getFirst().getType());
        assertEquals("success", events.getFirst().getData().get("status"));
    }

    @Test
    void appScopedEventsMustUseSharedStreamWhenRetainedLocalSessionIsInactive() {
        GenerationSession retainedSession = mock(GenerationSession.class);
        App app = new App();
        app.setId(1L);
        app.setTenantId(100L);
        when(retainedSession.taskId()).thenReturn("task-durable");
        when(retainedSession.taskRequest()).thenReturn(
                new GenerationTaskRequest(app, "build", user(2L)));
        when(retainedSession.isActive()).thenReturn(false);
        when(retainedSession.asFlux()).thenReturn(
                Flux.just(GenerationStreamEvent.aiDelta("stale")));
        when(sessionRegistry.get(1L)).thenReturn(retainedSession);
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        when(eventStream.stream("task-durable")).thenReturn(
                Flux.just(GenerationStreamEvent.aiDelta("remote")));

        List<GenerationStreamEvent> events = service
                .eventsForLatestNonTerminalAppTask(1L, user(2L))
                .collectList().block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals("remote", events.getFirst().getText());
        verify(eventStream).stream("task-durable");
    }

    @Test
    void eventsMustUseSharedTransportWhenTaskRunsOnAnotherNode() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        when(eventStream.available("task-durable")).thenReturn(true);
        when(eventStream.stream("task-durable"))
                .thenReturn(Flux.just(GenerationStreamEvent.aiDelta("remote")));

        List<GenerationStreamEvent> events = service.events("task-durable", user(2L))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(1, events.size());
        assertEquals("remote", events.getFirst().getText());
    }

    @Test
    void sequencedEventsMustForwardReplayCursorToSharedTransport() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(record(2L)));
        when(eventStream.stream("task-durable", 41L)).thenReturn(Flux.just(
                SequencedGenerationEvent.event(42L, GenerationStreamEvent.aiDelta("resumed")),
                SequencedGenerationEvent.complete(43L)
        ));

        List<SequencedGenerationEvent> events = service
                .sequencedEvents("task-durable", 41L, user(2L))
                .collectList()
                .block(Duration.ofSeconds(1));

        assertEquals(List.of(42L, 43L), events.stream()
                .map(SequencedGenerationEvent::sequence)
                .toList());
        verify(eventStream).stream("task-durable", 41L);
    }

    private DurableGenerationTaskRecord record(long userId) {
        return record(userId, GenerationTaskStatus.QUEUED);
    }

    private DurableGenerationTaskRecord record(long userId, GenerationTaskStatus status) {
        return new DurableGenerationTaskRecord(
                "task-durable", 1L, userId, 100L, "heavy_generation", status, "queued", "等待执行",
                SUBMITTED_AT, SUBMITTED_AT.plusSeconds(1_200), false, null,
                "worker-a", SUBMITTED_AT.plusSeconds(30), SUBMITTED_AT,
                0, 0L, status.isTerminal() ? SUBMITTED_AT.plusSeconds(30) : null, null);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }
}
