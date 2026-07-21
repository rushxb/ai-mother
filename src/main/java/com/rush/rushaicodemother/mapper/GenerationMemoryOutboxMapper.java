package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface GenerationMemoryOutboxMapper {

    @Select("""
            SELECT taskId, tenantId, appId, userId, status, memorySummary, memoryIndexAttempts
            FROM generation_task
            WHERE status IN ('success', 'failed', 'cancelled', 'deadline_exceeded')
              AND memorySummary IS NOT NULL
              AND memorySummary <> ''
              AND memoryIndexedAt IS NULL
              AND memoryIndexAttempts < #{maxAttempts}
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
                                       @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE generation_task
            SET memoryIndexAttempts = memoryIndexAttempts + 1,
                memoryIndexError = NULL,
                memoryIndexLeaseOwner = #{leaseOwner},
                memoryIndexLeaseUntil = #{leaseUntil},
                updateTime = #{claimedAt}
            WHERE taskId = #{taskId}
              AND memoryIndexedAt IS NULL
              AND memoryIndexAttempts = #{expectedAttempts}
              AND memoryIndexAttempts < #{maxAttempts}
              AND (memoryIndexNextAttemptAt IS NULL OR memoryIndexNextAttemptAt <= #{claimedAt})
              AND (memoryIndexLeaseOwner IS NULL OR memoryIndexLeaseUntil IS NULL
                   OR memoryIndexLeaseUntil < #{claimedAt})
              AND isDelete = 0
            """)
    int claim(@Param("taskId") String taskId,
              @Param("expectedAttempts") int expectedAttempts,
              @Param("maxAttempts") int maxAttempts,
              @Param("leaseOwner") String leaseOwner,
              @Param("claimedAt") LocalDateTime claimedAt,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET memoryIndexedAt = #{indexedAt},
                memoryIndexError = NULL,
                memoryIndexNextAttemptAt = NULL,
                memoryIndexLeaseOwner = NULL,
                memoryIndexLeaseUntil = NULL,
                updateTime = #{indexedAt}
            WHERE taskId = #{taskId}
              AND memoryIndexedAt IS NULL
              AND memoryIndexLeaseOwner = #{leaseOwner}
              AND isDelete = 0
            """)
    int markIndexed(@Param("taskId") String taskId,
                    @Param("leaseOwner") String leaseOwner,
                    @Param("indexedAt") LocalDateTime indexedAt);

    @Update("""
            UPDATE generation_task
            SET memoryIndexError = #{error},
                memoryIndexNextAttemptAt = #{nextAttemptAt},
                memoryIndexLeaseOwner = NULL,
                memoryIndexLeaseUntil = NULL,
                updateTime = #{failedAt}
            WHERE taskId = #{taskId}
              AND memoryIndexedAt IS NULL
              AND memoryIndexLeaseOwner = #{leaseOwner}
              AND isDelete = 0
            """)
    int markFailed(@Param("taskId") String taskId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("error") String error,
                   @Param("failedAt") LocalDateTime failedAt,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
