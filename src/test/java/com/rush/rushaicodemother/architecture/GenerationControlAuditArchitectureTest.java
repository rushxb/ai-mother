package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.aop.GenerationControlAuditAspect;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditEvent;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditProperties;
import com.rush.rushaicodemother.orchestration.governance.audit.GenerationControlAuditStore;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationControlAuditArchitectureTest {

    @Test
    void auditMustWrapAuthorizationAndRateLimitAsTheOutermostControlBoundary() {
        Order order = GenerationControlAuditAspect.class.getAnnotation(Order.class);

        assertNotNull(order);
        assertEquals(Ordered.HIGHEST_PRECEDENCE, order.value());
    }

    @Test
    void auditEventMustNotGrowFreeTextOrRequestMetadataFields() {
        Set<String> components = Arrays.stream(GenerationControlAuditEvent.class.getRecordComponents())
                .map(component -> component.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of(
                "eventId", "permission", "resourceType", "resourceId",
                "actorType", "actorUserId", "transport", "outcome",
                "resultCode", "startedAt", "completedAt", "expiresAt"), components);
        assertFalse(components.stream().anyMatch(name -> {
            String lower = name.toLowerCase();
            return lower.contains("message") || lower.contains("payload")
                    || lower.contains("prompt") || lower.contains("header")
                    || lower.contains("session") || lower.contains("ip")
                    || lower.contains("useragent") || lower.contains("exception");
        }));
    }

    @Test
    void applicationStoreMustExposeOnlyAppendCompleteAndExpiryDeletion() {
        Set<String> operations = Arrays.stream(GenerationControlAuditStore.class.getDeclaredMethods())
                .map(method -> method.getName())
                .collect(Collectors.toSet());

        assertEquals(Set.of("start", "complete", "deleteExpired"), operations);
        assertEquals(Duration.ofDays(90), GenerationControlAuditProperties.RETENTION);
        assertEquals(500, GenerationControlAuditProperties.CLEANUP_BATCH_SIZE);
    }

    @Test
    void migrationMustEnforceOneWayCompletionAndBoundedRetention() throws Exception {
        String migration = Files.readString(Path.of(
                "sql", "migrations", "V20260828_5__generation_control_audit.sql"));
        String lower = migration.toLowerCase();

        assertTrue(lower.contains("outcome = 'started'"));
        assertTrue(lower.contains("completedat is null"));
        assertTrue(lower.contains("completedat is not null"));
        assertTrue(lower.contains("expiresat > startedat"));
        assertTrue(lower.contains("index idx_generation_control_audit_expiry"));
        assertFalse(lower.contains("requestbody"));
        assertFalse(lower.contains("exceptionmessage"));
        assertFalse(lower.contains("useragent"));
        assertFalse(lower.contains("sessionid"));
    }
}
