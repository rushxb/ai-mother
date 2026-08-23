package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 约束 PREPARED 发布恢复始终在发布锁与数据库执行围栏内完成。 */
class GenerationWorkspacePublicationRecoveryArchitectureTest {

    @Test
    void expiredPreparedJournalRollbackMustRemainAnAtomicFencedTransition() throws Exception {
        String publicationService = source(
                "orchestration/workspace/GenerationWorkspacePublicationService.java");
        String journalMapper = source("mapper/GenerationWorkspacePublicationJournalMapper.java");
        int reconcileStart = publicationService.indexOf("public ReconciliationOutcome reconcile(");
        int lockStart = publicationService.indexOf("try (PublicationLock", reconcileStart);
        int expiryRollback = publicationService.indexOf(
                "rollbackPreparedIfOwningExecutionExpired(", reconcileStart);
        String transitionSql = journalMapper.substring(
                journalMapper.lastIndexOf("@Update(\"\"\"",
                        journalMapper.indexOf("int rollbackPreparedIfOwningExecutionExpired")),
                journalMapper.indexOf("int rollbackPreparedIfOwningExecutionExpired"));

        assertTrue(reconcileStart >= 0 && lockStart > reconcileStart && expiryRollback > lockStart,
                "PREPARED journal 的过期回滚必须在应用发布锁内执行");
        assertTrue(transitionSql.contains("publicationStatus = 'prepared'")
                        && transitionSql.contains("publicationCommittedAt IS NULL"),
                "过期回滚只能接纳尚未提交的 PREPARED journal");
        assertTrue(transitionSql.contains("status <> 'running'")
                        && transitionSql.contains("executionEpoch <> publicationExecutionEpoch")
                        && transitionSql.contains("leaseUntil IS NULL")
                        && transitionSql.contains("leaseUntil < #{expiredAt}"),
                "任务状态、执行轮次和租约失效判断必须保留在同一个数据库 CAS 中");
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", relativePath));
    }
}
