package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** MySQL-backed admission lock shared by every API instance. */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationTaskAdmissionRepository implements GenerationTaskAdmissionRepository {

    private final GenerationTaskRuntimeMapper mapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int lockUserAndCountNonTerminalTasks(Long userId) {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        Long lockedUserId = mapper.lockActiveUserForGenerationAdmission(userId);
        if (!Objects.equals(lockedUserId, userId)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "Generation user does not exist");
        }
        return mapper.countNonTerminalTasksByUserId(userId);
    }

    @Override
    public Optional<GenerationTaskIdempotencyRecord> findByIdempotencyKey(Long tenantId,
                                                                          Long userId,
                                                                          Long appId,
                                                                          String idempotencyKeyHash) {
        requirePositive(tenantId, "tenantId");
        requirePositive(userId, "userId");
        requirePositive(appId, "appId");
        if (idempotencyKeyHash == null || !idempotencyKeyHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("idempotencyKeyHash must be lowercase SHA-256");
        }
        GenerationTask task = mapper.selectBySubmissionIdempotency(
                tenantId, userId, appId, idempotencyKeyHash);
        if (task == null) {
            return Optional.empty();
        }
        return Optional.of(new GenerationTaskIdempotencyRecord(
                task.getTaskId(), task.getRoute(), task.getRequestFingerprint()));
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
