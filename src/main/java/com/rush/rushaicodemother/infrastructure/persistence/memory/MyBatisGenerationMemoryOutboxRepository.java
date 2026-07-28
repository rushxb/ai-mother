package com.rush.rushaicodemother.infrastructure.persistence.memory;

import com.rush.rushaicodemother.mapper.GenerationMemoryOutboxMapper;
import com.rush.rushaicodemother.mapper.projection.SemanticMemoryOutboxBacklogRow;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxItem;
import com.rush.rushaicodemother.memory.GenerationMemoryOutboxRepository;
import com.rush.rushaicodemother.memory.SemanticMemoryContract;
import com.rush.rushaicodemother.memory.SemanticMemoryOutboxBacklog;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * MyBatis生成记忆事务发件箱持久化仓储。
 */
@Repository
@RequiredArgsConstructor
public class MyBatisGenerationMemoryOutboxRepository implements GenerationMemoryOutboxRepository {
    private static final int MAX_BATCH_SIZE = 500;
    private static final int MAX_LEASE_OWNER_LENGTH = 128;
    private static final int MAX_ERROR_LENGTH = 1_000;

    private final GenerationMemoryOutboxMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    /**
 * 以原子方式声明批次。
 *
 * @param now 当前时间
 * @param leaseUntil {@code leaseUntil} 对应的调用参数
 * @param leaseOwner 租约所有者
 * @param batchSize 批次大小
 * @param maxAttempts 待处理的 {@code maxAttempts} 集合
 * @return 批次集合
 */
    @Override
    @Transactional
    public List<GenerationMemoryOutboxItem> claimBatch(Instant now,
                                                       Instant leaseUntil,
                                                       String leaseOwner,
                                                       int batchSize,
                                                       int maxAttempts) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (now == null || leaseUntil == null || !leaseUntil.isAfter(now)
                || leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH
                || batchSize <= 0 || batchSize > MAX_BATCH_SIZE || maxAttempts <= 0) {
            throw new IllegalArgumentException("memory outbox claim arguments are invalid");
        }
        LocalDateTime claimedAt = LocalDateTime.ofInstant(now, databaseZone);
        LocalDateTime claimedUntil = LocalDateTime.ofInstant(leaseUntil, databaseZone);
        int contractVersion = SemanticMemoryContract.INDEX_VERSION;
        List<GenerationTask> candidates = mapper.selectPending(
                claimedAt, batchSize, maxAttempts, contractVersion);
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        List<GenerationMemoryOutboxItem> claimed = new ArrayList<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationTask candidate : candidates) {
            int attempts = candidate.getMemoryIndexAttempts() == null
                    ? 0
                    : candidate.getMemoryIndexAttempts();
            boolean contractUpgrade = candidate.getMemoryIndexContractVersion() == null
                    || candidate.getMemoryIndexContractVersion() != contractVersion;
            if (mapper.claim(candidate.getTaskId(), attempts, maxAttempts, contractVersion,
                    leaseOwner, claimedAt, claimedUntil) == 1) {
                GenerationTaskStatus status = GenerationTaskStatus.fromValue(candidate.getStatus());
                if (status != null) {
                    claimed.add(new GenerationMemoryOutboxItem(
                            candidate.getTaskId(), candidate.getTenantId(), candidate.getAppId(),
                            candidate.getUserId(), status,
                            candidate.getUserPrompt(), candidate.getMemorySummary(),
                            candidate.getOrchestrationMode(), candidate.getTargetCodeGenType(),
                            contractUpgrade ? 1 : attempts + 1
                    ));
                }
            }
        }
        return List.copyOf(claimed);
    }

    /**
 * 更新{@code Indexed}的标记状态。
 *
 * @param taskId 任务编号
 * @param leaseOwner 租约所有者
 * @param indexedAt {@code indexedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean markIndexed(String taskId, String leaseOwner, Instant indexedAt) {
        validateTransition(taskId, leaseOwner, indexedAt);
        return mapper.markIndexed(taskId, leaseOwner, SemanticMemoryContract.INDEX_VERSION,
                LocalDateTime.ofInstant(indexedAt, databaseZone)) == 1;
    }

    /**
 * 更新失败的标记状态。
 *
 * @param taskId 任务编号
 * @param leaseOwner 租约所有者
 * @param error 错误
 * @param failedAt {@code failedAt} 对应的调用参数
 * @param nextAttemptAt {@code nextAttemptAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean markFailed(String taskId,
                              String leaseOwner,
                              String error,
                              Instant failedAt,
                              Instant nextAttemptAt) {
        validateTransition(taskId, leaseOwner, failedAt);
        if (nextAttemptAt == null || !nextAttemptAt.isAfter(failedAt)) {
            throw new IllegalArgumentException("memory outbox retry timestamp is invalid");
        }
        String normalizedError = error == null ? ""
                : error.length() <= MAX_ERROR_LENGTH ? error : error.substring(0, MAX_ERROR_LENGTH);
        return mapper.markFailed(
                taskId, leaseOwner, SemanticMemoryContract.INDEX_VERSION, normalizedError,
                LocalDateTime.ofInstant(failedAt, databaseZone),
                LocalDateTime.ofInstant(nextAttemptAt, databaseZone)) == 1;
    }

    /**
 * 返回{@code inspect}积压量。
 *
 * @param now 当前时间
 * @param maxAttempts 待处理的 {@code maxAttempts} 集合
 * @return {@code My}{@code Batis}生成记忆事务发件箱
 */
    @Override
    public SemanticMemoryOutboxBacklog inspectBacklog(Instant now, int maxAttempts) {
        if (now == null || maxAttempts <= 0) {
            throw new IllegalArgumentException("memory outbox backlog arguments are invalid");
        }
        SemanticMemoryOutboxBacklogRow row = mapper.inspectBacklog(
                LocalDateTime.ofInstant(now, databaseZone), maxAttempts,
                SemanticMemoryContract.INDEX_VERSION);
        return toBacklog(row);
    }

    private void validateTransition(String taskId, String leaseOwner, Instant changedAt) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")
                || leaseOwner == null || leaseOwner.isBlank()
                || leaseOwner.length() > MAX_LEASE_OWNER_LENGTH
                || changedAt == null) {
            throw new IllegalArgumentException("memory outbox transition arguments are invalid");
        }
    }

    /** 将当前对象转换为积压量。 */
    private SemanticMemoryOutboxBacklog toBacklog(SemanticMemoryOutboxBacklogRow row) {
        if (row == null) {
            return SemanticMemoryOutboxBacklog.empty();
        }
        return new SemanticMemoryOutboxBacklog(
                count(row.getPending()),
                count(row.getRetrying()),
                count(row.getLeased()),
                count(row.getDeadLetter()),
                row.getOldestPendingAt() == null
                        ? null
                        : row.getOldestPendingAt().atZone(databaseZone).toInstant()
        );
    }

    private long count(Long value) {
        return value == null ? 0 : value;
    }
}
