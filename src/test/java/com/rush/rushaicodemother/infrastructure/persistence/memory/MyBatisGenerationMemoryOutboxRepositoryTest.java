package com.rush.rushaicodemother.infrastructure.persistence.memory;

import com.rush.rushaicodemother.mapper.GenerationMemoryOutboxMapper;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxItem;
import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
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

class MyBatisGenerationMemoryOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-21T07:00:00Z");
    private static final Instant LEASE_UNTIL = NOW.plusSeconds(120);
    private static final int CURRENT_CONTRACT_VERSION = SemanticMemoryContract.INDEX_VERSION;

    @Test
    void claimBatchMustReturnOnlyRowsWonByAttemptCas() {
        GenerationMemoryOutboxMapper mapper = mock(GenerationMemoryOutboxMapper.class);
        MyBatisGenerationMemoryOutboxRepository repository =
                new MyBatisGenerationMemoryOutboxRepository(mapper);
        GenerationTask first = task("task-1", 2);
        GenerationTask raced = task("task-2", 4);
        when(mapper.selectPending(local(NOW), 2, 10, CURRENT_CONTRACT_VERSION))
                .thenReturn(List.of(first, raced));
        when(mapper.claim("task-1", 2, 10, CURRENT_CONTRACT_VERSION,
                "worker-a", local(NOW), local(LEASE_UNTIL)))
                .thenReturn(1);
        when(mapper.claim("task-2", 4, 10, CURRENT_CONTRACT_VERSION,
                "worker-a", local(NOW), local(LEASE_UNTIL)))
                .thenReturn(0);

        List<GenerationMemoryOutboxItem> claimed = repository.claimBatch(
                NOW, LEASE_UNTIL, "worker-a", 2, 10);

        assertEquals(1, claimed.size());
        GenerationMemoryOutboxItem item = claimed.getFirst();
        assertEquals("task-1", item.taskId());
        assertEquals(3L, item.tenantId());
        assertEquals(11L, item.appId());
        assertEquals(7L, item.userId());
        assertEquals(GenerationTaskStatus.SUCCESS, item.status());
        assertEquals("create an order page", item.userPrompt());
        assertEquals("build passed", item.memorySummary());
        assertEquals("graph", item.orchestrationMode());
        assertEquals("vue_project", item.targetCodeGenType());
        assertEquals(3, item.attempts());
    }

    @Test
    void contractUpgradeMustReceiveAFreshRetryBudgetWithoutBulkUpdatingHistory() {
        GenerationMemoryOutboxMapper mapper = mock(GenerationMemoryOutboxMapper.class);
        MyBatisGenerationMemoryOutboxRepository repository =
                new MyBatisGenerationMemoryOutboxRepository(mapper);
        GenerationTask legacy = task("task-v1", 10);
        legacy.setMemoryIndexContractVersion(1);
        legacy.setMemoryIndexedAt(local(NOW.minusSeconds(3_600)));
        when(mapper.selectPending(local(NOW), 1, 10, CURRENT_CONTRACT_VERSION))
                .thenReturn(List.of(legacy));
        when(mapper.claim("task-v1", 10, 10, CURRENT_CONTRACT_VERSION,
                "worker-a", local(NOW), local(LEASE_UNTIL)))
                .thenReturn(1);

        List<GenerationMemoryOutboxItem> claimed = repository.claimBatch(
                NOW, LEASE_UNTIL, "worker-a", 1, 10);

        assertEquals(1, claimed.size());
        assertEquals(1, claimed.getFirst().attempts());
    }

    @Test
    void transitionsMustBeFencedByTheLeaseOwnerAndBoundDiagnostics() {
        GenerationMemoryOutboxMapper mapper = mock(GenerationMemoryOutboxMapper.class);
        MyBatisGenerationMemoryOutboxRepository repository =
                new MyBatisGenerationMemoryOutboxRepository(mapper);
        when(mapper.markIndexed(
                "task-1", "stale-worker", CURRENT_CONTRACT_VERSION, local(NOW)))
                .thenReturn(0);

        assertFalse(repository.markIndexed("task-1", "stale-worker", NOW));

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        when(mapper.markFailed(
                eq("task-1"), eq("worker-a"), eq(CURRENT_CONTRACT_VERSION), error.capture(),
                eq(local(NOW)), eq(local(NOW.plusSeconds(30)))))
                .thenReturn(1);

        assertTrue(repository.markFailed(
                "task-1", "worker-a", "x".repeat(2_000), NOW, NOW.plusSeconds(30)));
        assertEquals(1_000, error.getValue().length());
    }

    @Test
    void backlogSnapshotMustPreserveCountsAndDatabaseTimezone() {
        GenerationMemoryOutboxMapper mapper = mock(GenerationMemoryOutboxMapper.class);
        MyBatisGenerationMemoryOutboxRepository repository =
                new MyBatisGenerationMemoryOutboxRepository(mapper);
        when(mapper.inspectBacklog(local(NOW), 10, CURRENT_CONTRACT_VERSION)).thenReturn(
                new SemanticMemoryOutboxBacklogRow(8L, 3L, 2L, 1L,
                        local(NOW.minusSeconds(600))));

        SemanticMemoryOutboxBacklog backlog = repository.inspectBacklog(NOW, 10);

        assertEquals(8, backlog.pending());
        assertEquals(3, backlog.retrying());
        assertEquals(2, backlog.leased());
        assertEquals(1, backlog.deadLetter());
        assertEquals(NOW.minusSeconds(600), backlog.oldestPendingAt());
    }

    @Test
    void claimAndTransitionSqlMustRetainLeaseExpiryAttemptCasAndOwnerFencing()
            throws NoSuchMethodException {
        String selectSql = sql(GenerationMemoryOutboxMapper.class.getMethod(
                "selectPending", LocalDateTime.class, int.class, int.class, int.class), Select.class);
        String claimSql = sql(GenerationMemoryOutboxMapper.class.getMethod(
                "claim", String.class, int.class, int.class, int.class, String.class,
                LocalDateTime.class, LocalDateTime.class), Update.class);
        String indexedSql = sql(GenerationMemoryOutboxMapper.class.getMethod(
                "markIndexed", String.class, String.class, int.class,
                LocalDateTime.class), Update.class);
        String failedSql = sql(GenerationMemoryOutboxMapper.class.getMethod(
                "markFailed", String.class, String.class, int.class, String.class,
                LocalDateTime.class, LocalDateTime.class), Update.class);
        String backlogSql = sql(GenerationMemoryOutboxMapper.class.getMethod(
                "inspectBacklog", LocalDateTime.class, int.class, int.class), Select.class);

        assertTrue(selectSql.contains("memoryindexattempts < #{maxattempts}"));
        assertTrue(selectSql.contains("userprompt"));
        assertTrue(selectSql.contains("orchestrationmode"));
        assertTrue(selectSql.contains("targetcodegentype"));
        assertTrue(selectSql.contains("memoryindexnextattemptat <= #{now}"));
        assertTrue(selectSql.contains("memoryindexleaseuntil < #{now}"));
        assertTrue(claimSql.contains("memoryindexattempts = #{expectedattempts}"));
        assertTrue(claimSql.contains("memoryindexleaseuntil < #{claimedat}"));
        assertTrue(claimSql.contains(
                "when memoryindexcontractversion <> #{contractversion} then 1"));
        assertTrue(claimSql.contains("memoryindexcontractversion = #{contractversion}"));
        assertTrue(indexedSql.contains("memoryindexleaseowner = #{leaseowner}"));
        assertTrue(indexedSql.contains("memoryindexcontractversion = #{contractversion}"));
        assertTrue(failedSql.contains("memoryindexleaseowner = #{leaseowner}"));
        assertTrue(backlogSql.contains("memoryindexattempts >= #{maxattempts}"));
        assertTrue(backlogSql.contains("as deadletter"));
        assertTrue(backlogSql.contains(
                "min(case when memoryindexcontractversion <> #{contractversion}"));
    }

    @Test
    void invalidLeaseAndRetryArgumentsMustFailBeforeDatabaseAccess() {
        GenerationMemoryOutboxMapper mapper = mock(GenerationMemoryOutboxMapper.class);
        MyBatisGenerationMemoryOutboxRepository repository =
                new MyBatisGenerationMemoryOutboxRepository(mapper);

        assertThrows(IllegalArgumentException.class,
                () -> repository.claimBatch(NOW, NOW, "worker-a", 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.claimBatch(NOW, LEASE_UNTIL, " ", 1, 10));
        assertThrows(IllegalArgumentException.class,
                () -> repository.markFailed("task-1", "worker-a", "failure", NOW, NOW));
    }

    @Test
    void replacingAMemorySummaryMustResetTheWholeIndexingContract()
            throws NoSuchMethodException {
        Method method = GenerationTraceMapper.class.getMethod(
                "updateTaskMemorySummary", Long.class, String.class, String.class,
                long.class, LocalDateTime.class);
        String updateSql = sql(method, Update.class);

        assertTrue(updateSql.contains("memoryindexedat = null"));
        assertTrue(updateSql.contains("memoryindexcontractversion = 0"));
        assertTrue(updateSql.contains("memoryindexattempts = 0"));
        assertTrue(updateSql.contains("memoryindexnextattemptat = null"));
        assertTrue(updateSql.contains("memoryindexleaseowner = null"));
        assertTrue(updateSql.contains("memoryindexleaseuntil = null"));
    }

    private GenerationTask task(String taskId, int attempts) {
        GenerationTask task = new GenerationTask();
        task.setTaskId(taskId);
        task.setTenantId(3L);
        task.setAppId(11L);
        task.setUserId(7L);
        task.setStatus(GenerationTaskStatus.SUCCESS.getValue());
        task.setUserPrompt("create an order page");
        task.setMemorySummary("build passed");
        task.setOrchestrationMode("graph");
        task.setTargetCodeGenType("vue_project");
        task.setMemoryIndexContractVersion(CURRENT_CONTRACT_VERSION);
        task.setMemoryIndexAttempts(attempts);
        return task;
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
