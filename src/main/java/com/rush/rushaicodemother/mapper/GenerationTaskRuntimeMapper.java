package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.entity.App;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 用于持久生成任务运行时所有权的显式 SQL 适配器。 */
public interface GenerationTaskRuntimeMapper {

    @Insert("""
            INSERT INTO generation_task (
                taskId, appId, userId, tenantId, idempotencyKeyHash, requestFingerprint,
                status, stage, route,
                runtimeSchemaVersion, runtimePayloadJson,
                submittedAt, deadlineAt, cancellationRequested,
                leaseOwner, leaseUntil, heartbeatAt, executionEpoch, attempt, version,
                dispatchAttempt,
                startTime, createTime, updateTime, isDelete
            ) VALUES (
                #{taskId}, #{appId}, #{userId}, #{tenantId},
                #{idempotencyKeyHash}, #{requestFingerprint},
                'queued', 'queued', #{route},
                #{runtimeSchemaVersion}, #{runtimePayloadJson},
                #{submittedAt}, #{deadlineAt}, 0,
                NULL, NULL, NULL, 0, 0, 0,
                0,
                #{submittedAt}, #{submittedAt}, #{submittedAt}, 0
            )
            """)
    int insertSubmittedTask(GenerationTask task);

    @Select("""
            SELECT id, tenantId
            FROM app
            WHERE id = #{appId} AND isDelete = 0
            FOR UPDATE
            """)
    App lockActiveApplicationForSubmission(@Param("appId") Long appId);

    @Select("""
            SELECT id
            FROM `user`
            WHERE id = #{userId} AND isDelete = 0
            FOR UPDATE
            """)
    Long lockActiveUserForGenerationAdmission(@Param("userId") Long userId);

    @Select("""
            SELECT COUNT(*)
            FROM generation_task
            WHERE appId = #{appId}
              AND status IN ('queued', 'running', 'waiting_approval')
              AND isDelete = 0
            """)
    int countNonTerminalTasksByAppId(@Param("appId") Long appId);

    @Select("""
            SELECT COUNT(*)
            FROM generation_task
            WHERE userId = #{userId}
              AND status IN ('queued', 'running', 'waiting_approval')
              AND isDelete = 0
            """)
    int countNonTerminalTasksByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT taskId, appId, route, status, submittedAt, deadlineAt, requestFingerprint
            FROM generation_task
            WHERE tenantId = #{tenantId}
              AND userId = #{userId}
              AND appId = #{appId}
              AND idempotencyKeyHash = #{idempotencyKeyHash}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectBySubmissionIdempotency(@Param("tenantId") Long tenantId,
                                                  @Param("userId") Long userId,
                                                  @Param("appId") Long appId,
                                                  @Param("idempotencyKeyHash") String idempotencyKeyHash);

    @Select("""
            SELECT taskId, appId, userId, tenantId, idempotencyKeyHash, requestFingerprint,
                   route, status, stage, stageMessage,
                   runtimeSchemaVersion, runtimePayloadJson,
                   dispatchAt, dispatchAttempt, dispatchError,
                   submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                   leaseOwner, leaseUntil, heartbeatAt, executionEpoch, attempt, version,
                   endTime, errorMessage
            FROM generation_task
            WHERE taskId = #{taskId} AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectRuntimeByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT taskId, appId, userId, tenantId, idempotencyKeyHash, requestFingerprint,
                   route, status, stage, stageMessage,
                   runtimeSchemaVersion, runtimePayloadJson,
                   dispatchAt, dispatchAttempt, dispatchError,
                   submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                   leaseOwner, leaseUntil, heartbeatAt, executionEpoch, attempt, version,
                   endTime, errorMessage
            FROM generation_task
            WHERE appId = #{appId}
              AND status IN ('queued', 'running', 'waiting_approval')
              AND isDelete = 0
            ORDER BY submittedAt DESC, id DESC
            LIMIT 1
            """)
    GenerationTask selectLatestNonTerminalByAppId(@Param("appId") Long appId);

    @Update("""
            UPDATE generation_task
            SET leaseOwner = #{leaseOwner}, heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{now}
            WHERE taskId = #{taskId}
              AND status = 'queued'
              AND cancellationRequested = 0
              AND (deadlineAt IS NULL OR deadlineAt > #{now})
              AND (leaseOwner IS NULL OR leaseUntil IS NULL OR leaseUntil < #{now})
              AND isDelete = 0
            """)
    int reserveQueuedTask(@Param("taskId") String taskId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("now") LocalDateTime now,
                          @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT taskId, leaseOwner, leaseUntil, executionEpoch
            FROM generation_task
            WHERE taskId = #{taskId}
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch > 0
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectOwnedLease(@Param("taskId") String taskId,
                                    @Param("leaseOwner") String leaseOwner);

    @Select("""
            SELECT COUNT(*)
            FROM generation_task
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{now}
              AND isDelete = 0
            """)
    int countCurrentExecutionFence(@Param("taskId") String taskId,
                                   @Param("leaseOwner") String leaseOwner,
                                   @Param("executionEpoch") long executionEpoch,
                                   @Param("now") LocalDateTime now);

    @Update("""
            UPDATE generation_task
            SET status = 'running',
                stage = CASE WHEN stage = 'queued' THEN 'starting' ELSE stage END,
                heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                attempt = attempt + 1, version = version + 1, updateTime = #{now}
            WHERE taskId = #{taskId}
              AND status = 'queued'
              AND cancellationRequested = 0
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{now}
              AND isDelete = 0
            """)
    int activateOwnedTask(@Param("taskId") String taskId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("executionEpoch") long executionEpoch,
                          @Param("now") LocalDateTime now,
                          @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET status = 'queued', stage = 'queued', stageMessage = #{reason},
                leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{releasedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{releasedAt}
              AND isDelete = 0
            """)
    int releaseOwnedTaskToQueue(@Param("taskId") String taskId,
                                @Param("leaseOwner") String leaseOwner,
                                @Param("executionEpoch") long executionEpoch,
                                @Param("releasedAt") LocalDateTime releasedAt,
                                @Param("reason") String reason);

    @Update("""
            UPDATE generation_task
            SET heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{now}
              AND isDelete = 0
            """)
    int renewOwnedLease(@Param("taskId") String taskId,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("executionEpoch") long executionEpoch,
                        @Param("now") LocalDateTime now,
                        @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET status = 'waiting_approval', stage = 'approval', stageMessage = #{stageMessage},
                leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{suspendedAt}
            WHERE taskId = #{taskId}
              AND status = 'running'
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{suspendedAt}
              AND isDelete = 0
            """)
    int suspendOwnedTaskForApproval(@Param("taskId") String taskId,
                                    @Param("leaseOwner") String leaseOwner,
                                    @Param("executionEpoch") long executionEpoch,
                                    @Param("stageMessage") String stageMessage,
                                    @Param("suspendedAt") LocalDateTime suspendedAt);

    @Update("""
            UPDATE generation_task
            SET status = 'queued', stage = 'queued', stageMessage = NULL,
                leaseOwner = #{leaseOwner}, leaseUntil = #{leaseUntil}, heartbeatAt = #{now},
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{now}
            WHERE taskId = #{taskId}
              AND status = 'waiting_approval'
              AND cancellationRequested = 0
              AND (deadlineAt IS NULL OR deadlineAt > #{now})
              AND leaseOwner IS NULL
              AND leaseUntil IS NULL
              AND isDelete = 0
            """)
    int requeueWaitingApprovalTask(@Param("taskId") String taskId,
                                   @Param("leaseOwner") String leaseOwner,
                                   @Param("now") LocalDateTime now,
                                   @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET status = 'waiting_approval', stage = 'approval', stageMessage = #{stageMessage},
                leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{restoredAt}
            WHERE taskId = #{taskId}
              AND status = 'queued'
              AND cancellationRequested = 0
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{restoredAt}
              AND isDelete = 0
            """)
    int restoreQueuedTaskToWaitingApproval(@Param("taskId") String taskId,
                                           @Param("leaseOwner") String leaseOwner,
                                           @Param("executionEpoch") long executionEpoch,
                                           @Param("stageMessage") String stageMessage,
                                           @Param("restoredAt") LocalDateTime restoredAt);

    @Update("""
            UPDATE generation_task
            SET status = 'waiting_approval', stage = 'approval', stageMessage = #{stageMessage},
                leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{restoredAt}
            WHERE taskId = #{taskId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
              AND cancellationRequested = 0
              AND (deadlineAt IS NULL OR deadlineAt > #{restoredAt})
              AND (leaseUntil IS NULL OR leaseUntil < #{restoredAt})
              AND isDelete = 0
            """)
    int restoreExpiredTaskForToolContinuation(@Param("taskId") String taskId,
                                              @Param("expectedStatus") String expectedStatus,
                                              @Param("expectedVersion") long expectedVersion,
                                              @Param("stageMessage") String stageMessage,
                                              @Param("restoredAt") LocalDateTime restoredAt);

    @Update("""
            UPDATE generation_task
            SET cancellationRequested = 1,
                cancellationReason = COALESCE(cancellationReason, #{reason}),
                version = version + 1, updateTime = #{requestedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running', 'waiting_approval')
              AND cancellationRequested = 0
              AND isDelete = 0
            """)
    int requestCancellation(@Param("taskId") String taskId,
                            @Param("reason") String reason,
                            @Param("requestedAt") LocalDateTime requestedAt);

    @Update("""
            UPDATE generation_task
            SET status = #{status}, stage = 'completed', stageMessage = NULL,
                endTime = #{completedAt},
                durationMs = GREATEST(0, TIMESTAMPDIFF(MICROSECOND, submittedAt, #{completedAt}) DIV 1000),
                errorMessage = #{reason}, leaseOwner = NULL, leaseUntil = NULL,
                heartbeatAt = NULL, executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND leaseOwner = #{leaseOwner}
              AND executionEpoch = #{executionEpoch}
              AND leaseUntil >= #{completedAt}
              AND isDelete = 0
            """)
    int completeOwnedTask(@Param("taskId") String taskId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("executionEpoch") long executionEpoch,
                          @Param("status") String status,
                          @Param("reason") String reason,
                          @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE generation_task
            SET status = #{status}, stage = 'completed', stageMessage = NULL,
                endTime = #{completedAt},
                durationMs = GREATEST(0, TIMESTAMPDIFF(MICROSECOND, submittedAt, #{completedAt}) DIV 1000),
                errorMessage = #{reason}, leaseOwner = NULL, leaseUntil = NULL,
                heartbeatAt = NULL, executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'waiting_approval')
              AND (leaseOwner IS NULL OR leaseUntil IS NULL OR leaseUntil < #{completedAt})
              AND isDelete = 0
            """)
    int completeUnownedTask(@Param("taskId") String taskId,
                            @Param("status") String status,
                            @Param("reason") String reason,
                            @Param("completedAt") LocalDateTime completedAt);

    @Select("""
            SELECT taskId, appId, status, leaseOwner, leaseUntil, deadlineAt,
                   cancellationRequested, cancellationReason, executionEpoch, version
            FROM generation_task
            WHERE (
                    (status = 'running' AND (leaseUntil IS NULL OR leaseUntil < #{now}))
                 OR (status = 'queued' AND deadlineAt IS NOT NULL AND deadlineAt <= #{now})
                 OR (status = 'queued' AND leaseOwner IS NOT NULL AND leaseUntil < #{now})
            )
              AND isDelete = 0
            ORDER BY COALESCE(leaseUntil, submittedAt) ASC, id ASC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectExpiredLeases(@Param("now") LocalDateTime now,
                                             @Param("limit") int limit);

    @Update("""
            UPDATE generation_task
            SET status = #{terminalStatus}, stage = 'completed', stageMessage = NULL,
                endTime = #{completedAt},
                durationMs = GREATEST(0, TIMESTAMPDIFF(MICROSECOND, submittedAt, #{completedAt}) DIV 1000),
                errorMessage = #{reason}, leaseOwner = NULL, leaseUntil = NULL,
                heartbeatAt = NULL, executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
              AND (leaseUntil IS NULL OR leaseUntil < #{completedAt})
              AND isDelete = 0
            """)
    int finalizeExpiredLease(@Param("taskId") String taskId,
                             @Param("expectedStatus") String expectedStatus,
                             @Param("expectedVersion") long expectedVersion,
                             @Param("terminalStatus") String terminalStatus,
                             @Param("completedAt") LocalDateTime completedAt,
                             @Param("reason") String reason);

    @Update("""
            UPDATE generation_task
            SET status = 'queued', stage = 'queued', stageMessage = #{reason},
                leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                dispatchAt = NULL, executionEpoch = executionEpoch + 1,
                version = version + 1, updateTime = #{requeuedAt}
            WHERE taskId = #{taskId}
              AND status = #{expectedStatus}
              AND version = #{expectedVersion}
              AND cancellationRequested = 0
              AND (deadlineAt IS NULL OR deadlineAt > #{requeuedAt})
              AND (leaseUntil IS NULL OR leaseUntil < #{requeuedAt})
              AND isDelete = 0
            """)
    int requeueExpiredLease(@Param("taskId") String taskId,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("expectedVersion") long expectedVersion,
                            @Param("requeuedAt") LocalDateTime requeuedAt,
                            @Param("reason") String reason);

    @Select("""
            SELECT taskId
            FROM generation_task
            WHERE status = 'queued'
              AND cancellationRequested = 0
              AND (deadlineAt IS NULL OR deadlineAt > #{now})
              AND leaseOwner IS NULL
              AND (dispatchAt IS NULL OR dispatchAt < #{dispatchedBefore})
              AND isDelete = 0
            ORDER BY submittedAt ASC, id ASC
            LIMIT #{limit}
            """)
    List<String> selectDispatchableQueuedTaskIds(@Param("now") LocalDateTime now,
                                                  @Param("dispatchedBefore") LocalDateTime dispatchedBefore,
                                                  @Param("limit") int limit);

    @Update("""
            UPDATE generation_task
            SET dispatchAt = #{dispatchedAt}, dispatchAttempt = dispatchAttempt + 1,
                dispatchError = NULL, updateTime = #{dispatchedAt}
            WHERE taskId = #{taskId}
              AND status = 'queued'
              AND isDelete = 0
            """)
    int recordDispatchSuccess(@Param("taskId") String taskId,
                              @Param("dispatchedAt") LocalDateTime dispatchedAt);

    @Update("""
            UPDATE generation_task
            SET dispatchAttempt = dispatchAttempt + 1,
                dispatchError = #{error}, updateTime = #{failedAt}
            WHERE taskId = #{taskId}
              AND status = 'queued'
              AND isDelete = 0
            """)
    int recordDispatchFailure(@Param("taskId") String taskId,
                              @Param("error") String error,
                              @Param("failedAt") LocalDateTime failedAt);
    @Select("""
            SELECT durationMs
            FROM generation_task
            WHERE route = #{route}
              AND status = 'success'
              AND durationMs > 0
              AND isDelete = 0
            ORDER BY endTime DESC, id DESC
            LIMIT #{limit}
            """)
    List<Long> selectRecentSuccessfulDurationsByRoute(@Param("route") String route,
                                                       @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*)
            FROM generation_task
            WHERE status = #{status}
              AND isDelete = 0
            """)
    int countRuntimeTasksByStatus(@Param("status") String status);

}
