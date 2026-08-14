package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.mapper.projection.GenerationTerminalEffectBacklogRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** generation_task 单行终态副作用 outbox 的显式 SQL。 */
public interface GenerationTerminalEffectMapper {

    @Select("""
            SELECT taskId, appId, userId, route,
                   terminalIntentSchemaVersion, terminalIntentPayloadJson,
                   terminalIntentExecutionEpoch, terminalEffectsAttempts,
                   terminalEffectsCompletedMask
            FROM generation_task
            WHERE terminalIntentFinalizedAt IS NOT NULL
              AND terminalEffectsCompletedAt IS NULL
              AND terminalEffectsAttempts < #{maxAttempts}
              AND (terminalEffectsNextAttemptAt IS NULL OR terminalEffectsNextAttemptAt <= #{now})
              AND (terminalEffectsLeaseUntil IS NULL OR terminalEffectsLeaseUntil < #{now})
              AND isDelete = 0
            ORDER BY terminalIntentFinalizedAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectPending(@Param("now") LocalDateTime now,
                                       @Param("limit") int limit,
                                       @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE generation_task
            SET terminalEffectsAttempts = terminalEffectsAttempts + 1,
                terminalEffectsLeaseOwner = #{leaseOwner},
                terminalEffectsLeaseUntil = #{leaseUntil},
                terminalEffectsError = NULL,
                terminalEffectsNextAttemptAt = NULL
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalEffectsCompletedAt IS NULL
              AND terminalEffectsAttempts = #{expectedAttempts}
              AND terminalEffectsAttempts < #{maxAttempts}
              AND (terminalEffectsNextAttemptAt IS NULL OR terminalEffectsNextAttemptAt <= #{now})
              AND (terminalEffectsLeaseUntil IS NULL OR terminalEffectsLeaseUntil < #{now})
              AND isDelete = 0
            """)
    int claim(@Param("taskId") String taskId,
              @Param("executionEpoch") long executionEpoch,
              @Param("expectedAttempts") int expectedAttempts,
              @Param("maxAttempts") int maxAttempts,
              @Param("leaseOwner") String leaseOwner,
              @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    /**
     * 隔离无法解码的终态意图，避免一条永久坏数据持续回滚整个扫描批次。
     *
     * <p>使用读取时的 attempts 和租约状态做 CAS；若记录已被其他实例领取，本次隔离不会覆盖其租约。</p>
     */
    @Update("""
            UPDATE generation_task
            SET terminalEffectsAttempts = #{maxAttempts},
                terminalEffectsError = #{error},
                terminalEffectsNextAttemptAt = NULL,
                terminalEffectsLeaseOwner = NULL,
                terminalEffectsLeaseUntil = NULL
            WHERE taskId = #{taskId}
              AND (terminalIntentExecutionEpoch = #{executionEpoch}
                   OR (terminalIntentExecutionEpoch IS NULL AND #{executionEpoch} = 0))
              AND terminalEffectsCompletedAt IS NULL
              AND terminalEffectsAttempts = #{expectedAttempts}
              AND terminalEffectsAttempts < #{maxAttempts}
              AND (terminalEffectsLeaseUntil IS NULL OR terminalEffectsLeaseUntil < #{now})
              AND isDelete = 0
            """)
    int markMalformed(@Param("taskId") String taskId,
                      @Param("executionEpoch") long executionEpoch,
                      @Param("expectedAttempts") int expectedAttempts,
                      @Param("maxAttempts") int maxAttempts,
                      @Param("error") String error,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE generation_task
            SET terminalEffectsCompletedMask = terminalEffectsCompletedMask | #{operationMask},
                updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalEffectsLeaseOwner = #{leaseOwner}
              AND terminalEffectsCompletedAt IS NULL
              AND (terminalEffectsCompletedMask & #{operationMask}) = 0
              AND isDelete = 0
            """)
    int markOperationCompleted(@Param("taskId") String taskId,
                               @Param("executionEpoch") long executionEpoch,
                               @Param("leaseOwner") String leaseOwner,
                               @Param("operationMask") long operationMask,
                               @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE generation_task
            SET terminalEffectsCompletedAt = #{completedAt},
                terminalEffectsLeaseOwner = NULL,
                terminalEffectsLeaseUntil = NULL,
                terminalEffectsError = NULL,
                terminalEffectsNextAttemptAt = NULL
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalEffectsLeaseOwner = #{leaseOwner}
              AND terminalEffectsCompletedAt IS NULL
              AND (terminalEffectsCompletedMask & #{requiredMask}) = #{requiredMask}
              AND isDelete = 0
            """)
    int markCompleted(@Param("taskId") String taskId,
                      @Param("executionEpoch") long executionEpoch,
                      @Param("leaseOwner") String leaseOwner,
                      @Param("requiredMask") long requiredMask,
                      @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE generation_task
            SET terminalEffectsError = #{error},
                terminalEffectsNextAttemptAt = #{nextAttemptAt},
                terminalEffectsLeaseOwner = NULL,
                terminalEffectsLeaseUntil = NULL
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalEffectsLeaseOwner = #{leaseOwner}
              AND terminalEffectsCompletedAt IS NULL
              AND isDelete = 0
            """)
    int markFailed(@Param("taskId") String taskId,
                   @Param("executionEpoch") long executionEpoch,
                   @Param("leaseOwner") String leaseOwner,
                   @Param("error") String error,
                   @Param("nextAttemptAt") LocalDateTime nextAttemptAt);

    @Select("""
            SELECT COALESCE(SUM(CASE WHEN terminalEffectsAttempts < #{maxAttempts}
                                          OR terminalEffectsLeaseUntil >= #{now}
                                     THEN 1 ELSE 0 END), 0) AS pending,
                   COALESCE(SUM(CASE WHEN terminalEffectsAttempts < #{maxAttempts}
                                          AND terminalEffectsNextAttemptAt > #{now}
                                     THEN 1 ELSE 0 END), 0) AS retrying,
                   COALESCE(SUM(CASE WHEN terminalEffectsLeaseOwner IS NOT NULL
                                          AND terminalEffectsLeaseUntil >= #{now}
                                     THEN 1 ELSE 0 END), 0) AS leased,
                   COALESCE(SUM(CASE WHEN terminalEffectsAttempts >= #{maxAttempts}
                                          AND (terminalEffectsLeaseUntil IS NULL
                                               OR terminalEffectsLeaseUntil < #{now})
                                     THEN 1 ELSE 0 END), 0) AS deadLetter,
                   MIN(CASE WHEN terminalEffectsAttempts < #{maxAttempts}
                                  OR terminalEffectsLeaseUntil >= #{now}
                            THEN terminalIntentFinalizedAt END) AS oldestPendingAt
            FROM generation_task
            WHERE terminalIntentFinalizedAt IS NOT NULL
              AND terminalEffectsCompletedAt IS NULL
              AND isDelete = 0
            """)
    GenerationTerminalEffectBacklogRow inspectBacklog(@Param("now") LocalDateTime now,
                                                        @Param("maxAttempts") int maxAttempts);

    @Select("""
            SELECT taskId, appId, route, terminalIntentExecutionEpoch,
                   terminalEffectsAttempts, terminalEffectsError,
                   terminalEffectsNextAttemptAt, terminalEffectsLeaseOwner,
                   terminalEffectsLeaseUntil, terminalIntentFinalizedAt
            FROM generation_task
            WHERE terminalIntentFinalizedAt IS NOT NULL
              AND terminalEffectsCompletedAt IS NULL
              AND isDelete = 0
            ORDER BY CASE WHEN terminalEffectsAttempts >= #{maxAttempts}
                                   AND (terminalEffectsLeaseUntil IS NULL
                                        OR terminalEffectsLeaseUntil < #{now})
                              THEN 0 ELSE 1 END,
                     terminalIntentFinalizedAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectOutstanding(@Param("now") LocalDateTime now,
                                            @Param("maxAttempts") int maxAttempts,
                                            @Param("limit") int limit);

    @Select("""
            SELECT terminalEffectsAttempts
            FROM generation_task
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalIntentFinalizedAt IS NOT NULL
              AND terminalEffectsCompletedAt IS NULL
              AND terminalEffectsAttempts >= #{maxAttempts}
              AND (terminalEffectsLeaseUntil IS NULL OR terminalEffectsLeaseUntil < #{now})
              AND isDelete = 0
            FOR UPDATE
            """)
    Integer selectReplayAttemptsForUpdate(@Param("taskId") String taskId,
                                           @Param("executionEpoch") long executionEpoch,
                                           @Param("maxAttempts") int maxAttempts,
                                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE generation_task
            SET terminalEffectsAttempts = 0,
                terminalEffectsError = NULL,
                terminalEffectsNextAttemptAt = #{requestedAt},
                terminalEffectsLeaseOwner = NULL,
                terminalEffectsLeaseUntil = NULL,
                version = version + 1,
                updateTime = #{requestedAt}
            WHERE taskId = #{taskId}
              AND terminalIntentExecutionEpoch = #{executionEpoch}
              AND terminalIntentFinalizedAt IS NOT NULL
              AND terminalEffectsCompletedAt IS NULL
              AND terminalEffectsAttempts = #{expectedAttempts}
              AND (terminalEffectsLeaseUntil IS NULL OR terminalEffectsLeaseUntil < #{requestedAt})
              AND isDelete = 0
            """)
    int replayDeadLetter(@Param("taskId") String taskId,
                         @Param("executionEpoch") long executionEpoch,
                         @Param("expectedAttempts") int expectedAttempts,
                         @Param("requestedAt") LocalDateTime requestedAt);

    @Insert("""
            INSERT INTO generation_terminal_effect_replay_audit (
                auditId, taskId, executionEpoch, previousAttempts,
                operatorUserId, requestedAt
            ) VALUES (
                #{auditId}, #{taskId}, #{executionEpoch}, #{previousAttempts},
                #{operatorUserId}, #{requestedAt}
            )
            """)
    int insertReplayAudit(@Param("auditId") String auditId,
                          @Param("taskId") String taskId,
                          @Param("executionEpoch") long executionEpoch,
                          @Param("previousAttempts") int previousAttempts,
                          @Param("operatorUserId") long operatorUserId,
                          @Param("requestedAt") LocalDateTime requestedAt);
}
