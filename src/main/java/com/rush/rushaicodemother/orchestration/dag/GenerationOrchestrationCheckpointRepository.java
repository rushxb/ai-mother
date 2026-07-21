package com.rush.rushaicodemother.orchestration.dag;

import java.util.Optional;

/** Durable storage port for orchestration checkpoint payloads. */
public interface GenerationOrchestrationCheckpointRepository {

    void save(GenerationOrchestrationTask task, String payloadJson, int payloadBytes);

    Optional<String> loadPayload(Long appId, String taskId);

    void delete(Long appId, String taskId, long executionEpoch);
}
