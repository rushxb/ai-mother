package com.rush.rushaicodemother.infrastructure.persistence.memory;

import com.rush.rushaicodemother.mapper.SemanticMemoryDeletionOutboxMapper;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import com.rush.rushaicodemother.memory.SemanticMemoryDeletionOutboxItem;
import com.rush.rushaicodemother.model.entity.SemanticMemoryDeletionOutboxEntity;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisSemanticMemoryDeletionOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-21T07:00:00Z");
    private static final Instant LEASE_UNTIL = NOW.plusSeconds(120);
    private static final String OPERATION_ID =
            "115739067c7ee55643f746bf4556e478955734d5d7873a84bd89e92791b218eb";

    @Test
    void enqueueMustUseAStableTenantApplicationOperationIdentity() {
        SemanticMemoryDeletionOutboxMapper mapper =
                mock(SemanticMemoryDeletionOutboxMapper.class);
        MyBatisSemanticMemoryDeletionOutboxRepository repository =
                new MyBatisSemanticMemoryDeletionOutboxRepository(mapper);

        repository.enqueueApplicationDeletion(3L, 11L, 7L, NOW);

        verify(mapper).enqueue(OPERATION_ID, 3L, 11L, 7L, local(NOW));
    }

    @Test
    void claimBatchMustReturnOnlyRowsWonByAttemptCas() {
        SemanticMemoryDeletionOutboxMapper mapper =
                mock(SemanticMemoryDeletionOutboxMapper.class);
        MyBatisSemanticMemoryDeletionOutboxRepository repository =
                new MyBatisSemanticMemoryDeletionOutboxRepository(mapper);
        SemanticMemoryDeletionOutboxEntity first = entity(OPERATION_ID, 2);
        SemanticMemoryDeletionOutboxEntity raced = entity("a".repeat(64), 5);
        when(mapper.selectPending(local(NOW), 2)).thenReturn(List.of(first, raced));
        when(mapper.claim(OPERATION_ID, 2, "worker-a", local(NOW), local(LEASE_UNTIL)))
                .thenReturn(1);
        when(mapper.claim("a".repeat(64), 5, "worker-a", local(NOW), local(LEASE_UNTIL)))
                .thenReturn(0);

        List<SemanticMemoryDeletionOutboxItem> claimed = repository.claimBatch(
                NOW, LEASE_UNTIL, "worker-a", 2);

        assertEquals(1, claimed.size());
        SemanticMemoryDeletionOutboxItem item = claimed.getFirst();
        assertEquals(OPERATION_ID, item.operationId());
        assertEquals(3L, item.tenantId());
        assertEquals(11L, item.appId());
        assertEquals(7L, item.requestedByUserId());
        assertEquals(3, item.attempts());
    }

    @Test
    void transitionsMustBeFencedByTheLeaseOwnerAndBoundDiagnostics() {
        SemanticMemoryDeletionOutboxMapper mapper =
                mock(SemanticMemoryDeletionOutboxMapper.class);
        MyBatisSemanticMemoryDeletionOutboxRepository repository =
                new MyBatisSemanticMemoryDeletionOutboxRepository(mapper);
        when(mapper.markCompleted(OPERATION_ID, "stale-worker", local(NOW))).thenReturn(0);

        assertFalse(repository.markCompleted(OPERATION_ID, "stale-worker", NOW));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        when(mapper.markFailed(
                eq(OPERATION_ID), eq("worker-a"), error.capture(),
                eq(local(NOW)), eq(local(NOW.plusSeconds(30)))))
                .thenReturn(1);

        assertTrue(repository.markFailed(
                OPERATION_ID, "worker-a", "x".repeat(2_000), NOW, NOW.plusSeconds(30)));
        assertEquals(1_000, error.getValue().length());
    }

    @Test
    void backlogSnapshotMustPreserveCountsAndDatabaseTimezone() {
        SemanticMemoryDeletionOutboxMapper mapper =
                mock(SemanticMemoryDeletionOutboxMapper.class);
        MyBatisSemanticMemoryDeletionOutboxRepository repository =
                new MyBatisSemanticMemoryDeletionOutboxRepository(mapper);
        when(mapper.inspectBacklog(local(NOW))).thenReturn(
                new SemanticMemoryOutboxBacklogRow(5L, 2L, 1L, 0L,
                        local(NOW.minusSeconds(300))));

        SemanticMemoryOutboxBacklog backlog = repository.inspectBacklog(NOW);

        assertEquals(5, backlog.pending());
        assertEquals(2, backlog.retrying());
        assertEquals(1, backlog.leased());
        assertEquals(0, backlog.deadLetter());
        assertEquals(NOW.minusSeconds(300), backlog.oldestPendingAt());
    }

    @Test
    void claimAndTransitionSqlMustRetainLeaseExpiryAttemptCasAndOwnerFencing()
            throws NoSuchMethodException {
        String selectSql = sql(SemanticMemoryDeletionOutboxMapper.class.getMethod(
                "selectPending", LocalDateTime.class, int.class), Select.class);
        String claimSql = sql(SemanticMemoryDeletionOutboxMapper.class.getMethod(
                "claim", String.class, int.class, String.class,
                LocalDateTime.class, LocalDateTime.class), Update.class);
        String completedSql = sql(SemanticMemoryDeletionOutboxMapper.class.getMethod(
                "markCompleted", String.class, String.class, LocalDateTime.class), Update.class);
        String failedSql = sql(SemanticMemoryDeletionOutboxMapper.class.getMethod(
                "markFailed", String.class, String.class, String.class,
                LocalDateTime.class, LocalDateTime.class), Update.class);
        String backlogSql = sql(SemanticMemoryDeletionOutboxMapper.class.getMethod(
                "inspectBacklog", LocalDateTime.class), Select.class);

        assertTrue(selectSql.contains("leaseuntil < #{now}"));
        assertTrue(selectSql.contains("nextattemptat <= #{now}"));
        assertTrue(claimSql.contains("attempts = #{expectedattempts}"));
        assertTrue(claimSql.contains("leaseuntil < #{now}"));
        assertTrue(completedSql.contains("leaseowner = #{leaseowner}"));
        assertTrue(failedSql.contains("leaseowner = #{leaseowner}"));
        assertTrue(backlogSql.contains("count(*) as pending"));
        assertTrue(backlogSql.contains("min(createtime) as oldestpendingat"));
    }

    @Test
    void invalidIdentityLeaseAndRetryArgumentsMustFailBeforeDatabaseAccess() {
        SemanticMemoryDeletionOutboxMapper mapper =
                mock(SemanticMemoryDeletionOutboxMapper.class);
        MyBatisSemanticMemoryDeletionOutboxRepository repository =
                new MyBatisSemanticMemoryDeletionOutboxRepository(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> repository.enqueueApplicationDeletion(0L, 11L, 7L, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> repository.claimBatch(NOW, NOW, "worker-a", 1));
        assertThrows(IllegalArgumentException.class,
                () -> repository.markCompleted("operation-1", "worker-a", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> repository.markFailed(OPERATION_ID, "worker-a", "failure", NOW, NOW));
    }

    private SemanticMemoryDeletionOutboxEntity entity(String operationId, int attempts) {
        return SemanticMemoryDeletionOutboxEntity.builder()
                .operationId(operationId)
                .tenantId(3L)
                .appId(11L)
                .requestedByUserId(7L)
                .attempts(attempts)
                .build();
    }

    private LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
    }

    private String sql(Method method, Class<?> annotationType) {
        String[] fragments;
        if (annotationType == Select.class) {
            fragments = method.getAnnotation(Select.class).value();
        } else {
            fragments = method.getAnnotation(Update.class).value();
        }
        return String.join(" ", fragments)
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }
}
