package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationToolApproval;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 生成工具审批数据访问映射器。
 */
public interface GenerationToolApprovalMapper {

    @Insert("""
            INSERT INTO generation_tool_approval (
                approvalId, taskId, appId, userId, action, requestJson,
                status, requestedAt, expiresAt,
                toolRequestId, toolName, argumentsDigest, checkpointJson, version
            ) VALUES (
                #{approvalId}, #{taskId}, #{appId}, #{userId}, #{action}, #{requestJson},
                'pending', #{requestedAt}, #{expiresAt},
                #{toolRequestId}, #{toolName}, #{argumentsDigest}, #{checkpointJson}, 0
            )
            ON DUPLICATE KEY UPDATE approvalId = approvalId
            """)
    int insertPending(GenerationToolApproval approval);

    @Select("""
            SELECT approvalId, taskId, appId, userId, action, requestJson, status,
                   requestedAt, expiresAt, decidedBy, decidedAt, consumedAt,
                   executionStartedAt, executionResult, executionAttempt,
                   toolRequestId, toolName, argumentsDigest, checkpointJson, version
            FROM generation_tool_approval
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
            LIMIT 1
            """)
    GenerationToolApproval selectOne(@Param("taskId") String taskId,
                                     @Param("action") String action,
                                     @Param("approvalId") String approvalId);

    @Update("""
            UPDATE generation_tool_approval
            SET toolRequestId = #{toolRequestId}, toolName = #{toolName},
                argumentsDigest = #{argumentsDigest}, checkpointJson = #{checkpointJson},
                version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'pending'
              AND expiresAt > #{capturedAt}
              AND toolRequestId IS NULL
              AND toolName IS NULL
              AND argumentsDigest IS NULL
              AND checkpointJson IS NULL
            """)
    int attachInvocationCheckpoint(@Param("taskId") String taskId,
                                   @Param("action") String action,
                                   @Param("approvalId") String approvalId,
                                   @Param("toolRequestId") String toolRequestId,
                                   @Param("toolName") String toolName,
                                   @Param("argumentsDigest") String argumentsDigest,
                                   @Param("checkpointJson") String checkpointJson,
                                   @Param("capturedAt") LocalDateTime capturedAt);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'approved', decidedBy = #{decidedBy}, decidedAt = #{decidedAt},
                version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'pending'
              AND expiresAt > #{decidedAt}
            """)
    int approve(@Param("taskId") String taskId,
                @Param("action") String action,
                @Param("approvalId") String approvalId,
                @Param("decidedBy") Long decidedBy,
                @Param("decidedAt") LocalDateTime decidedAt);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'rejected', decidedBy = #{decidedBy}, decidedAt = #{decidedAt},
                version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'pending'
              AND expiresAt > #{decidedAt}
            """)
    int reject(@Param("taskId") String taskId,
               @Param("action") String action,
               @Param("approvalId") String approvalId,
               @Param("decidedBy") Long decidedBy,
               @Param("decidedAt") LocalDateTime decidedAt);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'executing', executionStartedAt = #{executionStartedAt},
                executionResult = NULL, executionAttempt = executionAttempt + 1,
                version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'approved'
              AND toolRequestId = #{toolRequestId}
              AND expiresAt > #{executionStartedAt}
              AND executionAttempt < #{maxAttempts}
            """)
    int beginExecution(@Param("taskId") String taskId,
                       @Param("action") String action,
                       @Param("approvalId") String approvalId,
                       @Param("toolRequestId") String toolRequestId,
                       @Param("executionStartedAt") LocalDateTime executionStartedAt,
                       @Param("maxAttempts") int maxAttempts);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'consumed', consumedAt = #{consumedAt},
                executionResult = #{executionResult}, version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'executing'
              AND toolRequestId = #{toolRequestId}
            """)
    int completeExecution(@Param("taskId") String taskId,
                          @Param("action") String action,
                          @Param("approvalId") String approvalId,
                          @Param("toolRequestId") String toolRequestId,
                          @Param("executionResult") String executionResult,
                          @Param("consumedAt") LocalDateTime consumedAt);

    @Select("""
            SELECT approvalId, taskId, appId, userId, action, requestJson, status,
                   requestedAt, expiresAt, decidedBy, decidedAt, consumedAt,
                   executionStartedAt, executionResult, executionAttempt,
                   toolRequestId, toolName, argumentsDigest, checkpointJson, version
            FROM generation_tool_approval
            WHERE taskId = #{taskId}
              AND status IN ('approved', 'executing', 'consumed')
              AND checkpointJson IS NOT NULL
            ORDER BY id DESC
            LIMIT 1
            """)
    GenerationToolApproval selectRecoverableExecution(@Param("taskId") String taskId);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'approved', executionStartedAt = NULL,
                executionResult = NULL, version = version + 1
            WHERE taskId = #{taskId}
              AND action = #{action}
              AND approvalId = #{approvalId}
              AND status = 'executing'
              AND version = #{expectedVersion}
            """)
    int resetStaleExecution(@Param("taskId") String taskId,
                            @Param("action") String action,
                            @Param("approvalId") String approvalId,
                            @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE generation_tool_approval
            SET status = 'expired', version = version + 1
            WHERE status IN ('pending', 'approved')
              AND expiresAt <= #{now}
            ORDER BY expiresAt ASC, id ASC
            LIMIT #{limit}
            """)
    int expireBefore(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT approval.approvalId, approval.taskId, approval.appId, approval.userId,
                   approval.action, approval.requestJson, approval.status,
                   approval.requestedAt, approval.expiresAt, approval.decidedBy,
                   approval.decidedAt, approval.consumedAt, approval.executionStartedAt,
                   approval.executionResult, approval.executionAttempt, approval.toolRequestId,
                   approval.toolName, approval.argumentsDigest, approval.checkpointJson,
                   approval.version
            FROM generation_tool_approval approval
            INNER JOIN generation_task task ON task.taskId = approval.taskId
            WHERE approval.status IN ('approved', 'rejected', 'consumed', 'expired')
              AND approval.checkpointJson IS NOT NULL
              AND task.status = 'waiting_approval'
              AND task.isDelete = 0
            ORDER BY approval.expiresAt ASC, approval.id ASC
            LIMIT #{limit}
            """)
    List<GenerationToolApproval> selectWaitingContinuations(@Param("limit") int limit);
}
