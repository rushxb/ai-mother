package com.rush.rushaicodemother.orchestration.workspace;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** 文件系统/元数据发布传奇的相关事实来源。 */
public interface GenerationWorkspacePublicationJournalRepository {

    GenerationWorkspacePublicationJournalEntry prepare(
            GenerationWorkspacePublicationPointer candidate,
            Instant preparedAt);

    Optional<GenerationWorkspacePublicationJournalEntry> findByTaskId(String taskId);

    void markFilesystemActivated(GenerationWorkspacePublicationPointer pointer, Instant activatedAt);

    void markCommitted(GenerationWorkspacePublicationPointer pointer, Instant committedAt);

    void markRolledBack(GenerationWorkspacePublicationPointer pointer,
                        String error,
                        Instant rolledBackAt);

    /**
     * 仅当 PREPARED journal 的所属执行轮次已经失去有效租约时执行回滚。
     *
     * <p>实现必须把 journal 身份、任务执行轮次和租约过期判断放在同一个原子 CAS 中，
     * 防止对账扫描与 worker 心跳续租互相覆盖。</p>
     */
    boolean rollbackPreparedIfOwningExecutionExpired(
            GenerationWorkspacePublicationPointer pointer,
            String reason,
            Instant expiredAt);

    void markRollbackRequired(GenerationWorkspacePublicationPointer pointer,
                              String error,
                              Instant failedAt);

    void markSuperseded(GenerationWorkspacePublicationPointer pointer,
                        String reason,
                        Instant supersededAt);

    List<GenerationWorkspacePublicationJournalEntry> claimPending(
            Instant now,
            int limit,
            int maxAttempts,
            Duration retryDelay);

    void recordReconciliationFailure(GenerationWorkspacePublicationPointer pointer,
                                     String error,
                                     Instant failedAt);
}
