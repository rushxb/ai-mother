package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成记忆事务发件箱数据访问映射器。
 */
public interface GenerationMemoryOutboxMapper {

    @Select("""
            SELECT taskId, tenantId, appId, userId, status, userPrompt, memorySummary,
                   orchestrationMode, targetCodeGenType,
                   memoryIndexedAt, memoryIndexContractVersion, memoryIndexAttempts
            FROM generation_task
            WHERE status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
              AND memorySummary IS NOT NULL
              AND memorySummary <> ''
              AND (memoryIndexedAt IS NULL OR memoryIndexContractVersion <> #{contractVersion})
              AND (memoryIndexContractVersion <> #{contractVersion}
                   OR memoryIndexAttempts < #{maxAttempts})
              AND tenantId IS NOT NULL
              AND (memoryIndexNextAttemptAt IS NULL OR memoryIndexNextAttemptAt <= #{now})
              AND (memoryIndexLeaseOwner IS NULL OR memoryIndexLeaseUntil IS NULL
                   OR memoryIndexLeaseUntil < #{now})
              AND isDelete = 0
            ORDER BY COALESCE(endTime, updateTime) ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectPending(@Param("now") LocalDateTime now,
                                       @Param("limit") int limit,
                                       @Param("maxAttempts") int maxAttempts,
                                       @Param("contractVersion") int contractVersion);

    @Update("""
            UPDATE generation_task
            SET memoryIndexAttempts = CASE
                    WHEN memoryIndexContractVersion <> #{contractVersion} THEN 1
                    ELSE memoryIndexAttempts + 1
                END,
                memoryIndexedAt = CASE
                    WHEN memoryIndexContractVersion <> #{contractVersion} THEN NULL
                    ELSE memoryIndexedAt
                END,
                memoryIndexError = NULL,
                memoryIndexLeaseOwner = #{leaseOwner},
                memoryIndexLeaseUntil = #{leaseUntil},
                memoryIndexContractVersion = #{contractVersion},
                updateTime = #{claimedAt}
            WHERE taskId = #{taskId}
              AND (memoryIndexedAt IS NULL OR memoryIndexContractVersion <> #{contractVersion})
              AND memoryIndexAttempts = #{expectedAttempts}
              AND (memoryIndexContractVersion <> #{contractVersion}
                   OR memoryIndexAttempts < #{maxAttempts})
              AND (memoryIndexNextAttemptAt IS NULL OR memoryIndexNextAttemptAt <= #{claimedAt})
              AND (memoryIndexLeaseOwner IS NULL OR memoryIndexLeaseUntil IS NULL
                   OR memoryIndexLeaseUntil < #{claimedAt})
              AND isDelete = 0
            """)
    int claim(@Param("taskId") String taskId,
              @Param("expectedAttempts") int expectedAttempts,
              @Param("maxAttempts") int maxAttempts,
              @Param("contractVersion") int contractVersion,
              @Param("leaseOwner") String leaseOwner,
              @Param("claimedAt") LocalDateTime claimedAt,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET memoryIndexedAt = #{indexedAt},
                memoryIndexContractVersion = #{contractVersion},
                memoryIndexError = NULL,
                memoryIndexNextAttemptAt = NULL,
                memoryIndexLeaseOwner = NULL,
                memoryIndexLeaseUntil = NULL,
                updateTime = #{indexedAt}
            WHERE taskId = #{taskId}
              AND (memoryIndexedAt IS NULL OR memoryIndexContractVersion <> #{contractVersion})
              AND memoryIndexLeaseOwner = #{leaseOwner}
              AND isDelete = 0
            """)
    int markIndexed(@Param("taskId") String taskId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("contractVersion") int contractVersion,
                    @Param("indexedAt") LocalDateTime indexedAt);

    @Update("""
            UPDATE generation_task
            SET memoryIndexError = #{error},
                memoryIndexNextAttemptAt = #{nextAttemptAt},
                memoryIndexLeaseOwner = NULL,
                memoryIndexLeaseUntil = NULL,
                updateTime = #{failedAt}
            WHERE taskId = #{taskId}
              AND (memoryIndexedAt IS NULL OR memoryIndexContractVersion <> #{contractVersion})
              AND memoryIndexLeaseOwner = #{leaseOwner}
              AND isDelete = 0
            """)
    int markFailed(@Param("taskId") String taskId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("contractVersion") int contractVersion,
                   @Param("error") String error,
                   @Param("failedAt") LocalDateTime failedAt,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN memoryIndexContractVersion <> #{contractVersion}
                                          OR memoryIndexAttempts < #{maxAttempts}
                                     THEN 1 ELSE 0 END), 0) AS pending,
                   COALESCE(SUM(CASE WHEN (memoryIndexContractVersion <> #{contractVersion}
                                          OR memoryIndexAttempts < #{maxAttempts})
                                          AND memoryIndexNextAttemptAt > #{now}
                                     THEN 1 ELSE 0 END), 0) AS retrying,
                   COALESCE(SUM(CASE WHEN (memoryIndexContractVersion <> #{contractVersion}
                                          OR memoryIndexAttempts < #{maxAttempts})
                                          AND memoryIndexLeaseOwner IS NOT NULL
                                          AND memoryIndexLeaseUntil >= #{now}
                                     THEN 1 ELSE 0 END), 0) AS leased,
                   COALESCE(SUM(CASE WHEN memoryIndexContractVersion = #{contractVersion}
                                          AND memoryIndexAttempts >= #{maxAttempts}
                                     THEN 1 ELSE 0 END), 0) AS deadLetter,
                   MIN(CASE WHEN memoryIndexContractVersion <> #{contractVersion}
                                  OR memoryIndexAttempts < #{maxAttempts}
                            THEN COALESCE(endTime, createTime) END) AS oldestPendingAt
            FROM generation_task
            WHERE status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
              AND memorySummary IS NOT NULL
              AND memorySummary <> ''
              AND (memoryIndexedAt IS NULL OR memoryIndexContractVersion <> #{contractVersion})
              AND tenantId IS NOT NULL
              AND isDelete = 0
            """)
    SemanticMemoryOutboxBacklogRow inspectBacklog(@Param("now") LocalDateTime now,
                                                   @Param("maxAttempts") int maxAttempts,
                                                   @Param("contractVersion") int contractVersion);
}
