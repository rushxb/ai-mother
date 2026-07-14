package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.List;

/** 生成任务生命周期、构建诊断和模型用量的业务边界。 */
public interface GenerationTraceService {

    void startTask(GenerationTaskStartCommand command);

    void updateStage(String taskId, String stage, String stageMessage);

    void updateMemorySummary(String taskId, String memorySummary);

    void completeTask(String taskId, GenerationTaskStatus status, String errorMessage);

    void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event);

    void recordModelCall(GenerationModelCallCommand command);

    List<GenerationTaskTrace> listRecentTasksByAppId(Long appId, int limit);

    List<GenerationBuildTrace> listRecentBuildLogsByAppId(Long appId, int limit);

    List<GenerationBuildTrace> listBuildLogsByTaskId(String taskId, int limit);
}
