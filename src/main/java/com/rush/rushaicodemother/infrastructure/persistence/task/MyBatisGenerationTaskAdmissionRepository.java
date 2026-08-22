package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskSubmissionReceipt;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskAdmissionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskAdmissionRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskIdempotencyRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;

/** 每个 API 实例共享 MySQL 支持的准入锁。 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationTaskAdmissionRepository implements GenerationTaskAdmissionRepository {

    private final GenerationTaskRuntimeMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    /** 在固定锁顺序下读取租户、用户和应用准入事实。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public GenerationTaskAdmissionSnapshot lockScopeAndMeasure(Long tenantId, Long userId, Long appId) {
        requirePositive(tenantId, "tenantId");
        requirePositive(userId, "userId");
        requirePositive(appId, "appId");
        Long lockedTenantId = mapper.lockActiveTenantForGenerationAdmission(tenantId);
        if (!Objects.equals(lockedTenantId, tenantId)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "生成任务所属租户不存在或已停用");
        }
        Long lockedUserId = mapper.lockActiveUserForGenerationAdmission(userId);
        if (!Objects.equals(lockedUserId, userId)) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "生成任务用户不存在");
        }
        App lockedApp = mapper.lockActiveApplicationForSubmission(appId);
        if (lockedApp == null
                || !Objects.equals(lockedApp.getId(), appId)
                || !Objects.equals(lockedApp.getTenantId(), tenantId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "生成应用不存在或不属于当前租户");
        }
        LocalDateTime now = LocalDateTime.now(databaseZone);
        LocalDateTime periodStart = now.withDayOfMonth(1).toLocalDate().atStartOfDay();
        return new GenerationTaskAdmissionSnapshot(
                mapper.countNonTerminalTasksByUserId(userId),
                mapper.countNonTerminalTasksByAppId(appId),
                mapper.countNonTerminalTasksByTenantId(tenantId),
                mapper.countNonTerminalHeavyTasksByTenantId(tenantId),
                mapper.sumTenantGenerationCreditUsage(tenantId, periodStart, now)
        );
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
            throw new IllegalArgumentException(field + " 必须为正数");
        }
    }
}
