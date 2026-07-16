package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.model.enums.GenerationModelUsageSource;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.time.LocalDateTime;
import java.util.List;

/** 生成追踪模块的显式持久化边界。 */
public interface GenerationTracePersistenceService {

    boolean insertTask(NewTask task);

    TaskRecord findTaskByTaskId(String taskId);

    TaskRecord lockTaskByTaskId(String taskId);

    boolean enrichRuntimeTaskTrace(long recordId, NewTask task, LocalDateTime updateTime);

    void updateRunningTaskStage(long recordId, String stage, String stageMessage, LocalDateTime updateTime);

    void updateTaskMemorySummary(long recordId, String memorySummary, LocalDateTime updateTime);

    void completeRunningTask(long recordId,
                             GenerationTaskStatus status,
                             LocalDateTime endTime,
                             long durationMs,
                             String errorMessage);

    void insertBuildLog(NewBuildLog buildLog);

    boolean insertModelCall(NewModelCall modelCall);

    ModelCallRecord findModelCallByCallId(String callId);

    List<TaskRecord> listRecentTasksByAppId(long appId, int limit);

    List<BuildLogRecord> listRecentBuildLogsByAppId(long appId, int limit);

    List<BuildLogRecord> listBuildLogsByTaskId(String taskId, int limit);

    record NewTask(
            String taskId,
            long appId,
            long userId,
            String originalCodeGenType,
            String targetCodeGenType,
            String userPrompt,
            String enhancedPrompt,
            boolean requiresBuildValidation,
            String qualityGate,
            String orchestrationMode,
            LocalDateTime startTime
    ) {
    }

    record TaskRecord(
            long recordId,
            String taskId,
            long appId,
            long userId,
            String originalCodeGenType,
            String targetCodeGenType,
            GenerationTaskStatus status,
            String stage,
            String stageMessage,
            String userPrompt,
            String enhancedPrompt,
            boolean requiresBuildValidation,
            String qualityGate,
            String orchestrationMode,
            LocalDateTime startTime,
            LocalDateTime endTime,
            Long durationMs,
            String errorMessage,
            String memorySummary,
            LocalDateTime createTime
    ) {
    }

    record NewBuildLog(
            String taskId,
            long appId,
            long userId,
            String projectPath,
            String stage,
            boolean success,
            String summary,
            String report,
            String qualityGate,
            boolean willAutoRepair,
            LocalDateTime createTime
    ) {
    }

    record BuildLogRecord(
            String taskId,
            String stage,
            boolean success,
            String summary,
            String report,
            LocalDateTime createTime
    ) {
    }

    record NewModelCall(
            String callId,
            String taskId,
            long appId,
            long userId,
            String provider,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long latencyMs,
            String finishReason,
            GenerationModelUsageSource usageSource,
            LocalDateTime createTime
    ) {
    }

    record ModelCallRecord(
            String callId,
            String taskId,
            long appId,
            long userId,
            String provider,
            String model,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens,
            Long latencyMs,
            String finishReason,
            GenerationModelUsageSource usageSource
    ) {
    }
}
