package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
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
                   terminalIntentExecutionEpoch, terminalEffectsAttempts
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
              AND isDelete = 0
            """)
    int markCompleted(@Param("taskId") String taskId,
                      @Param("executionEpoch") long executionEpoch,
                      @Param("leaseOwner") String leaseOwner,
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
}
