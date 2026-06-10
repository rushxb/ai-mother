package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.GenerationBuildLog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface GenerationTraceService {

    void startTask(String taskId,
                   Long appId,
                   Long userId,
                   CodeGenTypeEnum originalType,
                   CodeGenTypeEnum targetType,
                   String userPrompt,
                   String enhancedPrompt,
                   boolean requiresBuildValidation,
                   String qualityGate,
                   String orchestrationMode);

    void updateStage(String taskId, String stage, String message);

    void updateMemorySummary(String taskId, String memorySummary);

    void completeTask(String taskId, String status, Instant startedAt, String errorMessage);

    void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event);

    void recordBuildResult(String taskId, Long appId, Long userId, GenerationStreamEvent event);

    void recordModelCall(String taskId, Long appId, Long userId, Map<String, Object> metadata);

    GenerationTask getByTaskId(String taskId);

    List<GenerationTask> listRecentTasksByAppId(Long appId, int limit);

    List<GenerationBuildLog> listRecentBuildLogsByAppId(Long appId, int limit);

    List<GenerationBuildLog> listBuildLogsByTaskId(String taskId, int limit);
}
