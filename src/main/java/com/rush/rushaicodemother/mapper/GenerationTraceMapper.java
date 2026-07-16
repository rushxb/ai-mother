package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationModelCall;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** 生成追踪模块的显式字段 SQL Mapper。 */
public interface GenerationTraceMapper {

    @Insert("""
            INSERT INTO generation_task (
                taskId, appId, userId, originalCodeGenType, targetCodeGenType,
                status, stage, stageMessage, userPrompt, enhancedPrompt,
                requiresBuildValidation, qualityGate, orchestrationMode, route,
                submittedAt, cancellationRequested, attempt, version,
                startTime, totalTokens, creditCost, creditCharged,
                createTime, updateTime, isDelete
            ) VALUES (
                #{taskId}, #{appId}, #{userId}, #{originalCodeGenType}, #{targetCodeGenType},
                #{status}, #{stage}, #{stageMessage}, #{userPrompt}, #{enhancedPrompt},
                #{requiresBuildValidation}, #{qualityGate}, #{orchestrationMode}, #{orchestrationMode},
                #{startTime}, 0, 1, 0,
                #{startTime}, 0, 0, 0, #{createTime}, #{updateTime}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertTask(GenerationTask task);

    @Select("""
            SELECT id, taskId, appId, userId, originalCodeGenType, targetCodeGenType,
                   status, stage, stageMessage, userPrompt, enhancedPrompt,
                   requiresBuildValidation, qualityGate, orchestrationMode,
                   startTime, endTime, durationMs, errorMessage, memorySummary, createTime
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectTaskByTaskId(@Param("taskId") String taskId);

    @Select("""
            SELECT id, taskId, appId, userId, originalCodeGenType, targetCodeGenType,
                   status, stage, stageMessage, userPrompt, enhancedPrompt,
                   requiresBuildValidation, qualityGate, orchestrationMode,
                   startTime, endTime, durationMs, errorMessage, memorySummary, createTime
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            FOR UPDATE
            """)
    GenerationTask selectTaskByTaskIdForUpdate(@Param("taskId") String taskId);

    @Update("""
            UPDATE generation_task
            SET originalCodeGenType = #{originalCodeGenType},
                targetCodeGenType = #{targetCodeGenType},
                userPrompt = #{userPrompt},
                enhancedPrompt = #{enhancedPrompt},
                requiresBuildValidation = #{requiresBuildValidation},
                qualityGate = #{qualityGate},
                orchestrationMode = #{orchestrationMode},
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND targetCodeGenType IS NULL
              AND userPrompt IS NULL
              AND orchestrationMode IS NULL
              AND isDelete = 0
            """)
    int enrichRunningTaskTrace(@Param("recordId") Long recordId,
                               @Param("originalCodeGenType") String originalCodeGenType,
                               @Param("targetCodeGenType") String targetCodeGenType,
                               @Param("userPrompt") String userPrompt,
                               @Param("enhancedPrompt") String enhancedPrompt,
                               @Param("requiresBuildValidation") int requiresBuildValidation,
                               @Param("qualityGate") String qualityGate,
                               @Param("orchestrationMode") String orchestrationMode,
                               @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET stage = #{stage},
                stageMessage = #{stageMessage},
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND isDelete = 0
            """)
    int updateRunningTaskStage(@Param("recordId") Long recordId,
                               @Param("stage") String stage,
                               @Param("stageMessage") String stageMessage,
                               @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET memorySummary = #{memorySummary},
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND isDelete = 0
            """)
    int updateTaskMemorySummary(@Param("recordId") Long recordId,
                                @Param("memorySummary") String memorySummary,
                                @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET status = #{status},
                stage = 'completed',
                stageMessage = NULL,
                endTime = #{endTime},
                durationMs = #{durationMs},
                errorMessage = #{errorMessage},
                leaseOwner = NULL,
                leaseUntil = NULL,
                heartbeatAt = NULL,
                version = version + 1,
                updateTime = #{endTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND isDelete = 0
            """)
    int completeRunningTask(@Param("recordId") Long recordId,
                            @Param("status") String status,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("durationMs") Long durationMs,
                            @Param("errorMessage") String errorMessage);

    @Insert("""
            INSERT INTO generation_build_log (
                taskId, appId, userId, projectPath, stage, success,
                summary, report, qualityGate, willAutoRepair, createTime, isDelete
            ) VALUES (
                #{taskId}, #{appId}, #{userId}, #{projectPath}, #{stage}, #{success},
                #{summary}, #{report}, #{qualityGate}, #{willAutoRepair}, #{createTime}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertBuildLog(GenerationBuildLog buildLog);

    @Insert("""
            INSERT INTO generation_model_call (
                callId, taskId, appId, userId, provider, model,
                promptTokens, completionTokens, totalTokens, latencyMs,
                finishReason, usageSource, rawMetadataJson, createTime, isDelete
            ) VALUES (
                #{callId}, #{taskId}, #{appId}, #{userId}, #{provider}, #{model},
                #{promptTokens}, #{completionTokens}, #{totalTokens}, #{latencyMs},
                #{finishReason}, #{usageSource}, #{rawMetadataJson}, #{createTime}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertModelCall(GenerationModelCall modelCall);

    @Select("""
            SELECT callId, taskId, appId, userId, provider, model,
                   promptTokens, completionTokens, totalTokens, latencyMs,
                   finishReason, usageSource
            FROM generation_model_call
            WHERE callId = #{callId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationModelCall selectModelCallByCallId(@Param("callId") String callId);

    @Select("""
            SELECT id, taskId, appId, userId, originalCodeGenType, targetCodeGenType,
                   status, stage, stageMessage, userPrompt, enhancedPrompt,
                   requiresBuildValidation, qualityGate, orchestrationMode,
                   startTime, endTime, durationMs, errorMessage, memorySummary, createTime
            FROM generation_task
            WHERE appId = #{appId}
              AND isDelete = 0
            ORDER BY createTime DESC, id DESC
            LIMIT #{limit}
            """)
    List<GenerationTask> selectRecentTasksByAppId(@Param("appId") Long appId,
                                                   @Param("limit") Integer limit);

    @Select("""
            SELECT taskId, stage, success, summary, report, createTime
            FROM generation_build_log
            WHERE appId = #{appId}
              AND isDelete = 0
            ORDER BY createTime DESC, id DESC
            LIMIT #{limit}
            """)
    List<GenerationBuildLog> selectRecentBuildLogsByAppId(@Param("appId") Long appId,
                                                           @Param("limit") Integer limit);

    @Select("""
            SELECT taskId, stage, success, summary, report, createTime
            FROM generation_build_log
            WHERE taskId = #{taskId}
              AND isDelete = 0
            ORDER BY createTime DESC, id DESC
            LIMIT #{limit}
            """)
    List<GenerationBuildLog> selectBuildLogsByTaskId(@Param("taskId") String taskId,
                                                      @Param("limit") Integer limit);
}
