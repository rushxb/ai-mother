package com.rush.rushaicodemother.orchestration.dag;

import java.util.Optional;

/** 用于编排检查点有效负载的耐用存储端口。 */
public interface GenerationOrchestrationCheckpointRepository {

    void save(GenerationOrchestrationTask task, String payloadJson, int payloadBytes);

    Optional<String> loadPayload(Long appId, String taskId);

    void delete(Long appId, String taskId, long executionEpoch);
}
