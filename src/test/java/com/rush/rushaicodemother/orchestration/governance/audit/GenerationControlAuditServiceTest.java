package com.rush.rushaicodemother.orchestration.governance.audit;

import com.rush.rushaicodemother.orchestration.governance.access.GenerationControlPermission;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationControlAuditServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-28T08:00:00Z");

    @Test
    void beginMustPersistOnlyBoundedIdentityAndRetentionFacts() {
        GenerationControlAuditStore store = mock(GenerationControlAuditStore.class);
        GenerationControlAuditProperties properties = new GenerationControlAuditProperties();
        GenerationControlAuditService service = new GenerationControlAuditService(
                store, properties, Clock.fixed(NOW, ZoneOffset.UTC));

        GenerationControlAuditHandle handle = service.begin(
                GenerationControlPermission.TASK_CANCEL,
                GenerationControlAuditResource.TASK,
                "task-1",
                GenerationControlAuditSubject.httpUser(7L));

        verify(store).start(any(GenerationControlAuditEvent.class));
        assertEquals(36, handle.eventId().length());
        assertEquals(NOW, handle.startedAt());
        assertEquals(NOW.plus(GenerationControlAuditProperties.RETENTION), handle.expiresAt());
    }

    @Test
    void unsafeResourceIdentityMustBeHashedInsteadOfPersistedVerbatim() {
        GenerationControlAuditStore store = mock(GenerationControlAuditStore.class);
        GenerationControlAuditService service = new GenerationControlAuditService(
                store, new GenerationControlAuditProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        service.begin(
                GenerationControlPermission.MODEL_CONFIGURE,
                GenerationControlAuditResource.MODEL,
                "sk-secret value with spaces",
                GenerationControlAuditSubject.httpUser(7L));

        verify(store).start(org.mockito.ArgumentMatchers.argThat(event ->
                event.resourceId().matches("sha256:[0-9a-f]{64}")
                        && !event.resourceId().contains("secret")));
    }

    @Test
    void systemExecutionMustFinalizeSuccessAndFailureWithoutStoringExceptionMessage() {
        GenerationControlAuditStore store = mock(GenerationControlAuditStore.class);
        when(store.complete(any(), any(), any(), any())).thenReturn(true);
        GenerationControlAuditService service = new GenerationControlAuditService(
                store, new GenerationControlAuditProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertEquals("ok", service.executeSystem(
                GenerationControlPermission.TASK_RECOVERY,
                GenerationControlAuditResource.TASK,
                "task-1",
                () -> "ok"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () ->
                service.executeSystem(
                        GenerationControlPermission.TASK_RECOVERY,
                        GenerationControlAuditResource.TASK,
                        "task-2",
                        () -> {
                            throw new IllegalStateException("password=must-not-be-persisted");
                        }));

        assertEquals("password=must-not-be-persisted", failure.getMessage());
        verify(store).complete(any(),
                org.mockito.ArgumentMatchers.eq(GenerationControlAuditOutcome.SUCCESS),
                org.mockito.ArgumentMatchers.eq("OK"), any());
        verify(store).complete(any(),
                org.mockito.ArgumentMatchers.eq(GenerationControlAuditOutcome.FAILED),
                org.mockito.ArgumentMatchers.eq("INTERNAL_ERROR"), any());
    }

    @Test
    void successfulSystemOperationMustNotBeReplayedWhenAuditCompletionIsUnavailable() {
        GenerationControlAuditStore store = mock(GenerationControlAuditStore.class);
        when(store.complete(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("audit database unavailable"));
        GenerationControlAuditService service = new GenerationControlAuditService(
                store, new GenerationControlAuditProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

        String result = service.executeSystem(
                GenerationControlPermission.TASK_RECOVERY,
                GenerationControlAuditResource.TASK,
                "task-1",
                () -> "already-completed");

        assertEquals("already-completed", result);
        verify(store).start(any(GenerationControlAuditEvent.class));
    }
}
