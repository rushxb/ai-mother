package com.rush.rushaicodemother.service.trace;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.List;

/** 生成任务生命周期、构建诊断和模型用量的业务边界。 */
public interface GenerationTraceService {

    void startTask(GenerationTaskStartCommand command);

    GenerationTaskTraceStartResult startOrTransitionTask(GenerationTaskStartCommand command);

    void updateStage(String taskId, String stage, String stageMessage);

    void updateMemorySummary(String taskId, String memorySummary);

    void completeTask(String taskId, GenerationTaskStatus status, String errorMessage);

    /**
     * 完成任务并记录 L3 结果质量证据。
     *
     * @param taskId 任务编号
     * @param status 目标终态
     * @param errorMessage 错误消息
     * @param outcomeQuality 结果质量证据；{@code null} 表示未采集
     */
    void completeTask(String taskId,
                      GenerationTaskStatus status,
                      String errorMessage,
                      GenerationOutcomeQuality outcomeQuality);

    void recordEvent(String taskId, Long appId, Long userId, GenerationStreamEvent event);

    void recordModelCall(GenerationModelCallCommand command);

    List<GenerationTaskTrace> listRecentTasksByAppId(Long appId, int limit);

    List<GenerationBuildTrace> listRecentBuildLogsByAppId(Long appId, int limit);

    List<GenerationBuildTrace> listBuildLogsByTaskId(String taskId, int limit);
}
