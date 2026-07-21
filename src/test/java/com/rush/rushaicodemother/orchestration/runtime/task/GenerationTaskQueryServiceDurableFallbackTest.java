package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.model.enums.TenantRole;
import com.rush.rushaicodemother.orchestration.GenerationSessionProperties;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.eventstream.GenerationEventStream;
import com.rush.rushaicodemother.orchestration.eventstream.SequencedGenerationEvent;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimate;
import com.rush.rushaicodemother.orchestration.runtime.task.progress.GenerationTaskProgressEstimator;
import com.rush.rushaicodemother.service.app.AppPersistenceService;
import com.rush.rushaicodemother.service.tenant.TenantAuthorizationService;
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

    @BeforeEach
    void setUp() {
        repository = mock(DurableGenerationTaskRepository.class);
        progressEstimator = mock(GenerationTaskProgressEstimator.class);
        eventStream = mock(GenerationEventStream.class);
        appPersistenceService = mock(AppPersistenceService.class);
        tenantAuthorizationService = mock(TenantAuthorizationService.class);
        service = new GenerationTaskQueryService(
                new GenerationSessionRegistry(new GenerationSessionProperties()), repository,
                progressEstimator, eventStream, appPersistenceService, tenantAuthorizationService);
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
    void terminalEventsMustFailHonestlyAfterReplayRetentionExpires() {
        when(repository.findByTaskId("task-durable")).thenReturn(Optional.of(
                record(2L, GenerationTaskStatus.SUCCESS)));

        assertThrows(BusinessException.class, () -> service.events("task-durable", user(2L)));
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
