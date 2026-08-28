package com.rush.rushaicodemother.orchestration.governance.audit;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationControlAuditRetentionServiceTest {

    @Test
    void cleanupMustDeleteOnlyExpiredEventsInABoundedBatch() {
        Instant now = Instant.parse("2026-08-28T08:00:00Z");
        GenerationControlAuditStore store = mock(GenerationControlAuditStore.class);
        GenerationControlAuditProperties properties = new GenerationControlAuditProperties();
        when(store.deleteExpired(now, GenerationControlAuditProperties.CLEANUP_BATCH_SIZE))
                .thenReturn(17);
        GenerationControlAuditRetentionService service = new GenerationControlAuditRetentionService(
                store, properties, Clock.fixed(now, ZoneOffset.UTC));

        assertEquals(17, service.deleteExpiredBatch());

        verify(store).deleteExpired(now, GenerationControlAuditProperties.CLEANUP_BATCH_SIZE);
    }
}
