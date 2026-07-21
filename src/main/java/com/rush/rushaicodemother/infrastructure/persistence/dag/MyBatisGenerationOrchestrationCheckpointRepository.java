package com.rush.rushaicodemother.infrastructure.persistence.dag;

import com.rush.rushaicodemother.mapper.GenerationOrchestrationCheckpointMapper;
import com.rush.rushaicodemother.model.entity.GenerationOrchestrationCheckpoint;
import com.rush.rushaicodemother.orchestration.dag.GenerationCheckpointPersistenceException;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationCheckpointRepository;
import com.rush.rushaicodemother.orchestration.dag.GenerationOrchestrationTask;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** MySQL-backed durable checkpoint repository for cross-instance DAG recovery. */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationOrchestrationCheckpointRepository
        implements GenerationOrchestrationCheckpointRepository {

    private final GenerationOrchestrationCheckpointMapper mapper;

    @Override
    @Transactional
    public void save(GenerationOrchestrationTask task, String payloadJson, int payloadBytes) {
        GenerationOrchestrationCheckpoint checkpoint = toCheckpoint(task, payloadJson, payloadBytes);
        if (mapper.updateCheckpointIfNotStale(checkpoint) == 1) {
            return;
        }
        try {
            if (mapper.insertCheckpoint(checkpoint) == 1) {
                return;
            }
        } catch (DuplicateKeyException duplicate) {
            if (mapper.updateCheckpointIfNotStale(checkpoint) == 1) {
                return;
            }
        }
        throw new GenerationCheckpointPersistenceException(
                GenerationCheckpointPersistenceException.Reason.STALE_EXECUTION_FENCE,
                "orchestration checkpoint was rejected by the durable execution fence");
    }

    @Override
    public Optional<String> loadPayload(Long appId, String taskId) {
        return Optional.ofNullable(mapper.selectPayload(appId, taskId));
    }

    @Override
    public void delete(Long appId, String taskId, long executionEpoch) {
        mapper.softDelete(appId, taskId, executionEpoch);
    }

    private GenerationOrchestrationCheckpoint toCheckpoint(GenerationOrchestrationTask task,
                                                           String payloadJson,
                                                           int payloadBytes) {
        LocalDateTime now = LocalDateTime.now();
        return GenerationOrchestrationCheckpoint.builder()
                .taskId(task.getTaskId())
                .appId(task.getAppId())
                .executionEpoch(task.getExecutionEpoch())
                .requestHash(task.getRequestHash())
                .status(task.getStatus())
                .runtimeState(task.getRuntimeState() == null ? null : task.getRuntimeState().name())
                .currentNode(task.getCurrentNode())
                .lastCompletedNode(task.getLastCompletedNode())
                .checkpointVersion(task.getCheckpointVersion())
                .payloadJson(payloadJson)
                .payloadBytes(payloadBytes)
                .createTime(task.getCreatedAt() == null ? now : task.getCreatedAt())
                .updateTime(task.getUpdatedAt() == null ? now : task.getUpdatedAt())
                .build();
    }
}
