package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Explicit SQL adapter for durable generation-task runtime ownership. */
public interface GenerationTaskRuntimeMapper {

    @Insert("""
            INSERT INTO generation_task (
                taskId, appId, userId, status, stage, route,
                submittedAt, deadlineAt, cancellationRequested,
                leaseOwner, leaseUntil, heartbeatAt, attempt, version,
                startTime, createTime, updateTime, isDelete
            ) VALUES (
                #{taskId}, #{appId}, #{userId}, 'queued', 'queued', #{route},
                #{submittedAt}, #{deadlineAt}, 0,
                #{leaseOwner}, #{leaseUntil}, #{submittedAt}, 0, 0,
                #{submittedAt}, #{submittedAt}, #{submittedAt}, 0
            )
            """)
    int insertSubmittedTask(GenerationTask task);

    @Select("""
            SELECT taskId, appId, userId, route, status, stage, stageMessage,
                   submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                   leaseOwner, leaseUntil, heartbeatAt, attempt, version,
                   endTime, errorMessage
            FROM generation_task
            WHERE taskId = #{taskId} AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectRuntimeByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT taskId, appId, userId, route, status, stage, stageMessage,
                   submittedAt, deadlineAt, cancellationRequested, cancellationReason,
                   leaseOwner, leaseUntil, heartbeatAt, attempt, version,
                   endTime, errorMessage
            FROM generation_task
            WHERE appId = #{appId}
              AND status IN ('queued', 'running')
              AND isDelete = 0
            ORDER BY submittedAt DESC, id DESC
            LIMIT 1
            """)
    GenerationTask selectLatestNonTerminalByAppId(@Param("appId") Long appId);

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
              AND leaseUntil >= #{now}
              AND isDelete = 0
            """)
    int activateOwnedTask(@Param("taskId") String taskId,
                          @Param("leaseOwner") String leaseOwner,
                          @Param("now") LocalDateTime now,
                          @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET heartbeatAt = #{now}, leaseUntil = #{leaseUntil},
                version = version + 1, updateTime = #{now}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND leaseOwner = #{leaseOwner}
              AND leaseUntil >= #{now}
              AND isDelete = 0
            """)
    int renewOwnedLease(@Param("taskId") String taskId,
                        @Param("leaseOwner") String leaseOwner,
                        @Param("now") LocalDateTime now,
                        @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("""
            UPDATE generation_task
            SET cancellationRequested = 1,
                cancellationReason = COALESCE(cancellationReason, #{reason}),
                version = version + 1, updateTime = #{requestedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
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
                heartbeatAt = NULL, version = version + 1, updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND status IN ('queued', 'running')
              AND isDelete = 0
            """)
    int completeNonTerminalTask(@Param("taskId") String taskId,
                                @Param("status") String status,
                                @Param("reason") String reason,
                                @Param("completedAt") LocalDateTime completedAt);

    @Update("""
            UPDATE generation_task
            SET leaseOwner = NULL, leaseUntil = NULL, heartbeatAt = NULL,
                version = version + 1, updateTime = #{completedAt}
            WHERE taskId = #{taskId}
              AND status = #{status}
              AND leaseOwner = #{leaseOwner}
              AND isDelete = 0
            """)
    int clearMatchingTerminalLease(@Param("taskId") String taskId,
                                   @Param("status") String status,
                                   @Param("leaseOwner") String leaseOwner,
                                   @Param("completedAt") LocalDateTime completedAt);

    @Select("""
            SELECT taskId, appId, status, leaseOwner, leaseUntil, deadlineAt,
                   cancellationRequested, cancellationReason, version
            FROM generation_task
            WHERE status IN ('queued', 'running')
              AND (leaseUntil IS NULL OR leaseUntil < #{now})
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
                heartbeatAt = NULL, version = version + 1, updateTime = #{completedAt}
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

}
