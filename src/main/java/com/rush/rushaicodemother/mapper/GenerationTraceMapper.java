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
              AND (
                    (#{leaseOwner} IS NULL AND executionEpoch = 0)
                    OR (leaseOwner = #{leaseOwner}
                        AND executionEpoch = #{executionEpoch}
                        AND leaseUntil >= #{updateTime})
              )
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
                               @Param("leaseOwner") String leaseOwner,
                               @Param("executionEpoch") long executionEpoch,
                               @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET originalCodeGenType = #{originalCodeGenType},
                targetCodeGenType = #{targetCodeGenType},
                enhancedPrompt = #{enhancedPrompt},
                requiresBuildValidation = #{requiresBuildValidation},
                qualityGate = #{qualityGate},
                orchestrationMode = #{orchestrationMode},
                route = #{orchestrationMode},
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND (
                    (#{leaseOwner} IS NULL AND executionEpoch = 0)
                    OR (leaseOwner = #{leaseOwner}
                        AND executionEpoch = #{executionEpoch}
                        AND leaseUntil >= #{updateTime})
              )
              AND isDelete = 0
            """)
    int transitionRunningTaskTrace(@Param("recordId") Long recordId,
                                   @Param("originalCodeGenType") String originalCodeGenType,
                                   @Param("targetCodeGenType") String targetCodeGenType,
                                   @Param("enhancedPrompt") String enhancedPrompt,
                                   @Param("requiresBuildValidation") int requiresBuildValidation,
                                   @Param("qualityGate") String qualityGate,
                                   @Param("orchestrationMode") String orchestrationMode,
                                   @Param("leaseOwner") String leaseOwner,
                                   @Param("executionEpoch") long executionEpoch,
                                   @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET stage = #{stage},
                stageMessage = #{stageMessage},
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND (
                    (#{leaseOwner} IS NULL AND executionEpoch = 0)
                    OR (leaseOwner = #{leaseOwner}
                        AND executionEpoch = #{executionEpoch}
                        AND leaseUntil >= #{updateTime})
              )
              AND isDelete = 0
            """)
    int updateRunningTaskStage(@Param("recordId") Long recordId,
                               @Param("stage") String stage,
                               @Param("stageMessage") String stageMessage,
                               @Param("leaseOwner") String leaseOwner,
                               @Param("executionEpoch") long executionEpoch,
                               @Param("updateTime") LocalDateTime updateTime);

    @Update("""
            UPDATE generation_task
            SET memorySummary = #{memorySummary},
                memoryIndexedAt = NULL,
                memoryIndexContractVersion = 0,
                memoryIndexAttempts = 0,
                memoryIndexError = NULL,
                memoryIndexNextAttemptAt = NULL,
                memoryIndexLeaseOwner = NULL,
                memoryIndexLeaseUntil = NULL,
                updateTime = #{updateTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND (
                    (#{leaseOwner} IS NULL AND executionEpoch = 0)
                    OR (leaseOwner = #{leaseOwner}
                        AND executionEpoch = #{executionEpoch}
                        AND leaseUntil >= #{updateTime})
              )
              AND isDelete = 0
            """)
    int updateTaskMemorySummary(@Param("recordId") Long recordId,
                                @Param("memorySummary") String memorySummary,
                                @Param("leaseOwner") String leaseOwner,
                                @Param("executionEpoch") long executionEpoch,
                                @Param("updateTime") LocalDateTime updateTime);

    /**
     * 完成运行中任务并写入终态。
     *
     * <p>L3 结果质量字段折叠进这条既有 UPDATE：不引入独立写入路径，天然与积分结算同事务，
     * 也不增加数据库往返。每个字段用 {@code COALESCE(#{值}, 列)} 保护 —— 传 {@code null}
     * 表示「未采集」而非「清空」，因此重试不会擦掉先前已采集的值。</p>
     */
    @Update("""
            UPDATE generation_task
            SET status = #{status},
                stage = 'completed',
                stageMessage = NULL,
                endTime = #{endTime},
                durationMs = #{durationMs},
                errorMessage = #{errorMessage},
                thinkingMode = COALESCE(#{thinkingMode}, thinkingMode),
                changedFileCount = COALESCE(#{changedFileCount}, changedFileCount),
                firstBuildPassed = COALESCE(#{firstBuildPassed}, firstBuildPassed),
                repairRounds = COALESCE(#{repairRounds}, repairRounds),
                firstPreviewMillis = COALESCE(#{firstPreviewMillis}, firstPreviewMillis),
                failureCategory = COALESCE(#{failureCategory}, failureCategory),
                reworkedAt = COALESCE(#{reworkedAt}, reworkedAt),
                distilledAt = COALESCE(#{distilledAt}, distilledAt),
                leaseOwner = NULL,
                leaseUntil = NULL,
                heartbeatAt = NULL,
                executionEpoch = executionEpoch + 1,
                version = version + 1,
                updateTime = #{endTime}
            WHERE id = #{recordId}
              AND status = 'running'
              AND (
                    (#{leaseOwner} IS NULL AND executionEpoch = 0)
                    OR (leaseOwner = #{leaseOwner}
                        AND executionEpoch = #{executionEpoch}
                        AND leaseUntil >= #{endTime})
              )
              AND isDelete = 0
            """)
    int completeRunningTask(@Param("recordId") Long recordId,
                            @Param("status") String status,
                            @Param("endTime") LocalDateTime endTime,
                            @Param("durationMs") Long durationMs,
                            @Param("errorMessage") String errorMessage,
                            @Param("leaseOwner") String leaseOwner,
                            @Param("executionEpoch") long executionEpoch,
                            @Param("thinkingMode") String thinkingMode,
                            @Param("changedFileCount") Integer changedFileCount,
                            @Param("firstBuildPassed") Integer firstBuildPassed,
                            @Param("repairRounds") Integer repairRounds,
                            @Param("firstPreviewMillis") Long firstPreviewMillis,
                            @Param("failureCategory") String failureCategory,
                            @Param("reworkedAt") LocalDateTime reworkedAt,
                            @Param("distilledAt") LocalDateTime distilledAt);

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
                callStatus, providerRequestId,
                promptTokens, completionTokens, totalTokens, latencyMs,
                finishReason, usageSource, errorCategory,
                requestHash, promptTemplateHash, toolSchemaHash, modelConfigHash,
                requestMessageCount, toolCount, rawMetadataJson, createTime, isDelete
            ) VALUES (
                #{callId}, #{taskId}, #{appId}, #{userId}, #{provider}, #{model},
                #{callStatus}, #{providerRequestId},
                #{promptTokens}, #{completionTokens}, #{totalTokens}, #{latencyMs},
                #{finishReason}, #{usageSource}, #{errorCategory},
                #{requestHash}, #{promptTemplateHash}, #{toolSchemaHash}, #{modelConfigHash},
                #{requestMessageCount}, #{toolCount}, #{rawMetadataJson}, #{createTime}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insertModelCall(GenerationModelCall modelCall);

    @Select("""
            SELECT callId, taskId, appId, userId, provider, model,
                   callStatus, providerRequestId,
                   promptTokens, completionTokens, totalTokens, latencyMs,
                   finishReason, usageSource, errorCategory,
                   requestHash, promptTemplateHash, toolSchemaHash, modelConfigHash,
                   requestMessageCount, toolCount, rawMetadataJson
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
