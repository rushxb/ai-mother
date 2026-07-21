package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.SemanticMemoryDeletionOutboxEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface SemanticMemoryDeletionOutboxMapper {

    @Insert("""
            INSERT INTO semantic_memory_deletion_outbox
                (operationId, operationType, tenantId, appId, requestedByUserId,
                 attempts, nextAttemptAt, createTime, updateTime)
            VALUES
                (#{operationId}, 'DELETE_APPLICATION', #{tenantId}, #{appId}, #{requestedByUserId},
                 0, #{createdAt}, #{createdAt}, #{createdAt})
            ON DUPLICATE KEY UPDATE
                requestedByUserId = VALUES(requestedByUserId),
                nextAttemptAt = CASE
                    WHEN completedAt IS NULL THEN LEAST(nextAttemptAt, VALUES(nextAttemptAt))
                    ELSE nextAttemptAt
                END,
                updateTime = VALUES(updateTime)
            """)
    int enqueue(@Param("operationId") String operationId,
                @Param("tenantId") Long tenantId,
                @Param("appId") Long appId,
                @Param("requestedByUserId") Long requestedByUserId,
                @Param("createdAt") LocalDateTime createdAt);

    @Select("""
            SELECT id, operationId, operationType, tenantId, appId, requestedByUserId,
                   attempts, nextAttemptAt, leaseOwner, leaseUntil, lastError,
                   completedAt, createTime, updateTime
            FROM semantic_memory_deletion_outbox
            WHERE operationType = 'DELETE_APPLICATION'
              AND completedAt IS NULL
              AND nextAttemptAt <= #{now}
              AND (leaseOwner IS NULL OR leaseUntil IS NULL OR leaseUntil < #{now})
            ORDER BY nextAttemptAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<SemanticMemoryDeletionOutboxEntity> selectPending(@Param("now") LocalDateTime now,
                                                            @Param("limit") int limit);

    @Update("""
            UPDATE semantic_memory_deletion_outbox
            SET leaseOwner = #{leaseOwner},
                leaseUntil = #{leaseUntil},
                attempts = attempts + 1,
                lastError = NULL,
                updateTime = #{now}
            WHERE operationId = #{operationId}
              AND operationType = 'DELETE_APPLICATION'
              AND completedAt IS NULL
              AND attempts = #{expectedAttempts}
              AND nextAttemptAt <= #{now}
              AND (leaseOwner IS NULL OR leaseUntil IS NULL OR leaseUntil < #{now})
            """)
    int claim(@Param("operationId") String operationId,
              @Param("expectedAttempts") int expectedAttempts,
              @Param("leaseOwner") String leaseOwner,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE semantic_memory_deletion_outbox
            SET completedAt = #{completedAt},
                leaseOwner = NULL,
                leaseUntil = NULL,
                lastError = NULL,
                updateTime = #{completedAt}
            WHERE operationId = #{operationId}
              AND operationType = 'DELETE_APPLICATION'
              AND completedAt IS NULL
              AND leaseOwner = #{leaseOwner}
            """)
    int markCompleted(@Param("operationId") String operationId,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE semantic_memory_deletion_outbox
            SET nextAttemptAt = #{nextAttemptAt},
                leaseOwner = NULL,
                leaseUntil = NULL,
                lastError = #{error},
                updateTime = #{failedAt}
            WHERE operationId = #{operationId}
              AND operationType = 'DELETE_APPLICATION'
              AND completedAt IS NULL
              AND leaseOwner = #{leaseOwner}
            """)
    int markFailed(@Param("operationId") String operationId,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("error") String error,
                   @Param("failedAt") LocalDateTime failedAt,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);
}
