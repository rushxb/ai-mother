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

    /**
     * 对账次数上限只能抑制仍由存活 worker 持有的 PREPARED 噪声，不能把安全关键状态变成死信。
     *
     * <p>FILESYSTEM_ACTIVATED 已经对用户切换了版本指针，ROLLBACK_REQUIRED 仍需恢复文件系统；
     * 两者若在达到次数上限后永久退出扫描，任务恢复会一直等待 journal，用户任务无法终态化。
     * PREPARED 也必须在所属执行租约失效后重新进入 claim，才能执行原子回滚。</p>
     */
    @Test
    void exhaustedCriticalJournalMustRemainClaimableForResolution() throws Exception {
        String mapper = source("mapper/GenerationWorkspacePublicationJournalMapper.java");
        int selectMethod = mapper.indexOf("List<GenerationTask> selectPending(");
        int claimMethod = mapper.indexOf("int claim(");
        String selectSql = normalizeSql(mapper.substring(
                mapper.lastIndexOf("@Select(\"\"\"", selectMethod), selectMethod));
        String claimSql = normalizeSql(mapper.substring(
                mapper.lastIndexOf("@Update(\"\"\"", claimMethod), claimMethod));

        assertCriticalRecoveryEligibility(selectSql, "#{now}", "selectPending");
        assertCriticalRecoveryEligibility(claimSql, "#{claimedat}", "claim");
    }

    private String source(String relativePath) throws Exception {
        return Files.readString(Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother", relativePath));
    }

    private String normalizeSql(String source) {
        return source.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    private void assertCriticalRecoveryEligibility(String sql,
                                                    String expiryParameter,
                                                    String operation) {
        assertTrue(sql.contains("publicationattempts < #{maxattempts} or publicationstatus in "
                        + "('filesystem_activated', 'rollback_required')"),
                operation + " 必须让已激活文件系统或待回滚状态越过次数上限继续对账");
        assertTrue(sql.contains("publicationstatus = 'prepared' "
                        + "and publicationcommittedat is null")
                        && sql.contains("leaseuntil < " + expiryParameter),
                operation + " 必须让所属租约已失效的 PREPARED journal 重新进入对账");
    }
}
