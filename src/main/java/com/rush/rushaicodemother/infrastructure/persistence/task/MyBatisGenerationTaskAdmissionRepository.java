package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/** 每个 API 实例共享 MySQL 支持的准入锁。 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationTaskAdmissionRepository implements GenerationTaskAdmissionRepository {

    private final GenerationTaskRuntimeMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    /**
 * 返回锁用户{@code And}数量{@code Non}{@code Terminal}任务。
 *
 * @param userId 用户编号
 * @return 计算或处理后的数值结果
 */
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

    /**
 * 查找匹配的按{@code Idempotency}键。
 *
 * @param tenantId 租户编号
 * @param userId 用户编号
 * @param appId 应用编号
 * @param idempotencyKeyHash {@code idempotencyKeyHash} 对应的调用参数
 * @return 可选的按{@code Idempotency}键；不存在时返回空值
 */
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
        GenerationTaskStatus status = GenerationTaskStatus.fromValue(task.getStatus());
        if (status == null || task.getAppId() == null
                || task.getSubmittedAt() == null || task.getDeadlineAt() == null) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "生成任务幂等记录缺少提交回执字段"
            );
        }
        return Optional.of(new GenerationTaskIdempotencyRecord(
                new GenerationTaskSubmissionReceipt(
                        task.getTaskId(),
                        task.getAppId(),
                        task.getRoute(),
                        status,
                        task.getSubmittedAt().atZone(databaseZone).toInstant(),
                        task.getDeadlineAt().atZone(databaseZone).toInstant()
                ),
                task.getRequestFingerprint()
        ));
    }

    private void requirePositive(Long value, String field) {
        if (value == null || value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
