package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.mapper.GenerationTraceMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommand;
import com.rush.rushaicodemother.orchestration.finalization.GenerationFinalizationCommandCodec;
import com.rush.rushaicodemother.orchestration.learning.GenerationScenarioDecisionSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLease;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommandCodec;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 用于持久生成任务运行时端口的 MyBatis 适配器。 */
@Repository
public class MyBatisDurableGenerationTaskRepository implements DurableGenerationTaskRepository {

    private static final int MAX_RECOVERY_BATCH_SIZE = 500;

    private final GenerationTaskRuntimeMapper mapper;
    private final GenerationTraceMapper traceMapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    public MyBatisDurableGenerationTaskRepository(GenerationTaskRuntimeMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public MyBatisDurableGenerationTaskRepository(GenerationTaskRuntimeMapper mapper,
                                                  GenerationTraceMapper traceMapper) {
        this.mapper = mapper;
        this.traceMapper = traceMapper;
    }

    /**
 * 创建{@code Submitted}。
 *
 * @param task 任务
 */
    @Override
    @Transactional
    public void createSubmitted(GenerationTaskSubmissionRecord task) {
        Objects.requireNonNull(task, "task");
        App lockedApp = mapper.lockActiveApplicationForSubmission(task.appId());
        if (lockedApp == null
                || !Objects.equals(lockedApp.getId(), task.appId())
                || !Objects.equals(lockedApp.getTenantId(), task.tenantId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation application does not exist");
        }
        GenerationTask duplicate = mapper.selectRuntimeByTaskId(task.taskId());
        if (duplicate != null) {
            validateDuplicateIdentity(duplicate, task);
            return;
        }
        if (mapper.countNonTerminalTasksByAppId(task.appId()) > 0) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Application already has a non-terminal generation task");
        }
        GenerationScenarioDecisionSnapshot scenario =
                GenerationScenarioDecisionSnapshot.from(task.command());
        GenerationTask entity = GenerationTask.builder()
                .taskId(task.taskId()).appId(task.appId()).userId(task.userId())
                .tenantId(task.tenantId())
                .idempotencyKeyHash(task.idempotencyKeyHash())
                .requestFingerprint(task.requestFingerprint())
                .route(task.route())
                .intentSignature(scenario.intentSignature())
                .intentProfileVersion(scenario.profileVersion())
                .routeDecisionVersion(scenario.decisionVersion())
                .routeEvidenceJson(scenario.evidenceJson())
                .routeAlternativesJson(scenario.alternativesJson())
                .routeReleaseIdentity(scenario.releaseIdentity())
                .submittedAt(toLocal(task.submittedAt())).deadlineAt(toLocal(task.deadlineAt()))
                .runtimeSchemaVersion(task.command().schemaVersion())
                .runtimePayloadJson(GenerationTaskCommandCodec.toJson(task.command()))
                .build();
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            requireOneRow(mapper.insertSubmittedTask(entity), "create submitted generation task");
        } catch (DuplicateKeyException duplicateKey) {
            GenerationTask existing = mapper.selectRuntimeByTaskId(task.taskId());
            if (existing != null) {
                validateDuplicateIdentity(existing, task);
                return;
            }
            throw new BusinessException(
                    ErrorCode.CONFLICT_ERROR,
                    "Generation submission idempotency key is already occupied",
                    duplicateKey
            );
        }
    }

    /**
 * 查找匹配的按任务编号。
 *
 * @param taskId 任务编号
 * @return 可选的按任务编号；不存在时返回空值
 */
    @Override
    public Optional<DurableGenerationTaskRecord> findByTaskId(String taskId) {
        requireTaskId(taskId);
        return Optional.ofNullable(mapper.selectRuntimeByTaskId(taskId)).map(this::toRecord);
    }

    /**
 * 查找匹配的{@code Latest}{@code Non}{@code Terminal}按应用编号。
 *
 * @param appId 应用编号
 * @return 可选的{@code Latest}{@code Non}{@code Terminal}按应用编号；不存在时返回空值
 */
    @Override
    public Optional<DurableGenerationTaskRecord> findLatestNonTerminalByAppId(Long appId) {
        if (appId == null || appId <= 0) throw new IllegalArgumentException("appId must be positive");
        return Optional.ofNullable(mapper.selectLatestNonTerminalByAppId(appId)).map(this::toRecord);
    }

    /**
 * 查找匹配的命令按任务编号。
 *
 * @param taskId 任务编号
 * @return 可选的命令按任务编号；不存在时返回空值
 */
    @Override
    public Optional<GenerationTaskCommand> findCommandByTaskId(String taskId) {
        requireTaskId(taskId);
        GenerationTask entity = mapper.selectRuntimeByTaskId(taskId);
        if (entity == null) {
            return Optional.empty();
        }
        if (!GenerationTaskCommand.supportsSchemaVersion(entity.getRuntimeSchemaVersion())
                || entity.getRuntimePayloadJson() == null
                || entity.getRuntimePayloadJson().isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task runtime command is missing or unsupported");
        }
        try {
            GenerationTaskCommand command = GenerationTaskCommandCodec.fromJson(entity.getRuntimePayloadJson());
            if (!Objects.equals(command.taskId(), entity.getTaskId())
                    || !Objects.equals(command.appId(), entity.getAppId())
                    || !Objects.equals(command.userId(), entity.getUserId())
                    || !Objects.equals(command.route(), entity.getRoute())) {
                throw new IllegalStateException("generation task command identity mismatch");
            }
            return Optional.of(command);
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task runtime command is corrupted", malformed);
        }
    }

    @Override
    public void prepareFinalizationIntent(GenerationFinalizationCommand command, Instant preparedAt) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(preparedAt, "preparedAt");
        GenerationExecutionFence fence = Objects.requireNonNull(
                command.executionFence(), "终态意图必须提供执行围栏");
        String payload = GenerationFinalizationCommandCodec.toJson(command);
        int changed = mapper.prepareFinalizationIntent(
                command.taskId(), command.appId(), fence.leaseOwner(), fence.executionEpoch(),
                GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION, payload, toLocal(preparedAt));
        if (changed == 1) {
            return;
        }
        GenerationFinalizationCommand existing = findFinalizationIntent(
                command.taskId(), fence.executionEpoch()).orElseThrow(() ->
                new BusinessException(ErrorCode.OPERATION_ERROR, "生成终态意图写入被执行围栏拒绝"));
        if (!existing.equals(command)) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成终态意图与已冻结命令冲突");
        }
    }

    @Override
    public Optional<GenerationFinalizationCommand> findFinalizationIntent(String taskId,
                                                                           long executionEpoch) {
        requireTaskId(taskId);
        if (executionEpoch <= 0) {
            throw new IllegalArgumentException("终态意图执行轮次必须为正数");
        }
        GenerationTask entity = mapper.selectRuntimeByTaskId(taskId);
        if (entity == null
                || !Long.valueOf(executionEpoch).equals(entity.getTerminalIntentExecutionEpoch())) {
            return Optional.empty();
        }
        if (!Integer.valueOf(GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION)
                .equals(entity.getTerminalIntentSchemaVersion())
                || entity.getTerminalIntentPayloadJson() == null
                || entity.getTerminalIntentPayloadJson().isBlank()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成终态意图缺失或协议版本不受支持");
        }
        try {
            GenerationFinalizationCommand command = GenerationFinalizationCommandCodec.fromJson(
                    entity.getTerminalIntentPayloadJson());
            if (!taskId.equals(command.taskId())
                    || command.executionFence() == null
                    || command.executionFence().executionEpoch() != executionEpoch
                    || !Objects.equals(command.appId(), entity.getAppId())) {
                throw new IllegalStateException("生成终态意图身份不一致");
            }
            return Optional.of(command);
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "生成终态意图已损坏", malformed);
        }
    }

    @Override
    public boolean isCurrentFence(GenerationExecutionFence fence, Instant now) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(now, "now");
        return mapper.countCurrentExecutionFence(
                fence.taskId(), fence.leaseOwner(), fence.executionEpoch(), toLocal(now)) == 1;
    }

    /**
 * 加载当前{@code Load}。
 *
 * @return 当前{@code Load}
 */
    @Override
    public GenerationTaskLoadSnapshot loadCurrentLoad() {
        return new GenerationTaskLoadSnapshot(
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.QUEUED.getValue()),
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.RUNNING.getValue()),
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.WAITING_APPROVAL.getValue())
        );
    }

    /**
 * 返回{@code reserve}{@code Queued}。
 *
 * @param taskId 任务编号
 * @param owner 所有者
 * @param now 当前时间
 * @param until {@code until} 对应的调用参数
 * @return 可选的{@code My}{@code Batis}持久生成任务；不存在时返回空值
 */
    @Override
    @Transactional
    public Optional<GenerationTaskLease> reserveQueued(String taskId,
                                                       String owner,
                                                       Instant now,
                                                       Instant until) {
        requireLeaseArguments(taskId, owner, now, until);
        if (mapper.reserveQueuedTask(taskId, owner, toLocal(now), toLocal(until)) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireOwnedLease(taskId, owner));
    }

    /**
 * 返回{@code activate}。
 *
 * @param lease 租约
 * @param now 当前时间
 * @param until {@code until} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean activate(GenerationTaskLease lease, Instant now, Instant until) {
        requireLeaseArguments(lease, now, until);
        return mapper.activateOwnedTask(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                toLocal(now), toLocal(until)) == 1;
    }

    /**
 * 释放{@code Claim}{@code To}{@code Queue}。
 *
 * @param lease 租约
 * @param releasedAt {@code releasedAt} 对应的调用参数
 * @param reason 原因
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean releaseClaimToQueue(GenerationTaskLease lease,
                                       Instant releasedAt,
                                       String reason) {
        Objects.requireNonNull(lease, "lease");
        if (releasedAt == null) {
            throw new IllegalArgumentException("generation task release identity is incomplete");
        }
        return mapper.releaseOwnedTaskToQueue(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                toLocal(releasedAt), normalizeReason(reason)) == 1;
    }

    /**
 * 返回{@code renew}租约。
 *
 * @param lease 租约
 * @param now 当前时间
 * @param until {@code until} 对应的调用参数
 * @return {@code My}{@code Batis}持久生成任务
 */
    @Override
    @Transactional
    public GenerationTaskLeaseRenewal renewLease(GenerationTaskLease lease,
                                                 Instant now,
                                                 Instant until) {
        requireLeaseArguments(lease, now, until);
        if (mapper.renewOwnedLease(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                toLocal(now), toLocal(until)) != 1) {
            return GenerationTaskLeaseRenewal.lost();
        }
        GenerationTask current = mapper.selectRuntimeByTaskId(lease.taskId());
        return current == null ? GenerationTaskLeaseRenewal.lost() : GenerationTaskLeaseRenewal.renewed(
                lease.renewedUntil(until),
                Integer.valueOf(1).equals(current.getCancellationRequested()),
                current.getCancellationReason());
    }

    /**
 * 返回{@code suspend}{@code For}审批。
 *
 * @param lease 租约
 * @param stageMessage 阶段消息
 * @param suspendedAt {@code suspendedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean suspendForApproval(GenerationTaskLease lease,
                                      String stageMessage,
                                      Instant suspendedAt) {
        Objects.requireNonNull(lease, "lease");
        if (suspendedAt == null) {
            throw new IllegalArgumentException("approval suspension identity is incomplete");
        }
        String normalizedMessage = stageMessage == null || stageMessage.isBlank()
                ? "waiting_for_tool_approval"
                : stageMessage.trim();
        return mapper.suspendOwnedTaskForApproval(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                normalizedMessage, toLocal(suspendedAt)) == 1;
    }

    /**
 * 返回{@code requeue}执行后审批。
 *
 * @param taskId 任务编号
 * @param owner 所有者
 * @param now 当前时间
 * @param until {@code until} 对应的调用参数
 * @return 可选的{@code My}{@code Batis}持久生成任务；不存在时返回空值
 */
    @Override
    @Transactional
    public Optional<GenerationTaskLease> requeueAfterApproval(String taskId,
                                                              String owner,
                                                              Instant now,
                                                              Instant until) {
        requireLeaseArguments(taskId, owner, now, until);
        if (mapper.requeueWaitingApprovalTask(
                taskId, owner, toLocal(now), toLocal(until)) != 1) {
            return Optional.empty();
        }
        return Optional.of(requireOwnedLease(taskId, owner));
    }

    /**
 * 返回恢复{@code Waiting}执行后{@code Dispatch}失败。
 *
 * @param lease 租约
 * @param stageMessage 阶段消息
 * @param restoredAt {@code restoredAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean restoreWaitingAfterDispatchFailure(GenerationTaskLease lease,
                                                      String stageMessage,
                                                      Instant restoredAt) {
        Objects.requireNonNull(lease, "lease");
        if (restoredAt == null) {
            throw new IllegalArgumentException("approval dispatch restoration identity is incomplete");
        }
        String normalizedMessage = stageMessage == null || stageMessage.isBlank()
                ? "approval_dispatch_retry"
                : stageMessage.trim();
        return mapper.restoreQueuedTaskToWaitingApproval(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                normalizedMessage, toLocal(restoredAt)) == 1;
    }

    /**
 * 返回恢复{@code Waiting}执行后{@code Stale}工具执行。
 *
 * @param candidate 候选
 * @param stageMessage 阶段消息
 * @param restoredAt {@code restoredAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean restoreWaitingAfterStaleToolExecution(GenerationTaskRecoveryCandidate candidate,
                                                         String stageMessage,
                                                         Instant restoredAt) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(restoredAt, "restoredAt");
        String normalizedMessage = stageMessage == null || stageMessage.isBlank()
                ? "tool_execution_recovery"
                : stageMessage.trim();
        return mapper.restoreExpiredTaskForToolContinuation(
                candidate.taskId(), candidate.status().getValue(), candidate.version(),
                normalizedMessage, toLocal(restoredAt)) == 1;
    }

    /**
 * 返回请求{@code Cancellation}。
 *
 * @param taskId 任务编号
 * @param reason 原因
 * @param requestedAt {@code requestedAt} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean requestCancellation(String taskId, String reason, Instant requestedAt) {
        requireTaskId(taskId);
        Objects.requireNonNull(requestedAt, "requestedAt");
        String normalized = reason == null || reason.isBlank() ? "user_requested" : reason.trim();
        if (mapper.requestCancellation(taskId, normalized, toLocal(requestedAt)) == 1) return true;
        return findByTaskId(taskId).map(DurableGenerationTaskRecord::cancellationRequested).orElse(false);
    }

    /**
 * 完成{@code Owned}并持久化终态。
 *
 * @param lease 租约
 * @param status 目标状态
 * @param reason 原因
 * @param completedAt 完成时间
 */
    @Override
    @Transactional
    public void completeOwned(GenerationTaskLease lease,
                              GenerationTaskStatus status,
                              String reason,
                              Instant completedAt) {
        Objects.requireNonNull(lease, "lease");
        if (status == null || !status.isTerminal()) throw new IllegalArgumentException("status must be terminal");
        Objects.requireNonNull(completedAt, "completedAt");
        int changed = mapper.completeOwnedTask(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                status.getValue(), normalizeReason(reason), toLocal(completedAt));
        if (changed == 1) return;
        requireIdempotentTerminalStatus(lease.taskId(), status);
    }

    /**
 * 将无主任务更新为指定终态。
 *
 * @param taskId 任务编号
 * @param status 目标状态
 * @param reason 原因
 * @param completedAt 完成时间
 */
    @Override
    @Transactional
    public void completeUnowned(String taskId,
                                GenerationTaskStatus status,
                                String reason,
                                Instant completedAt) {
        requireTaskId(taskId);
        if (status == null || !status.isTerminal()) throw new IllegalArgumentException("status must be terminal");
        Objects.requireNonNull(completedAt, "completedAt");
        GenerationTask task = mapper.selectRuntimeByTaskId(taskId);
        if (task == null || task.getAppId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist");
        }
        GenerationFinalizationCommand terminalCommand = terminalCommand(
                taskId, task.getAppId(), status, reason,
                unownedEffectFence(task));
        int changed = mapper.completeUnownedTask(
                taskId, status.getValue(), normalizeReason(reason), toLocal(completedAt),
                GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION,
                GenerationFinalizationCommandCodec.toJson(terminalCommand),
                terminalCommand.executionFence().executionEpoch());
        if (changed == 1) return;
        requireIdempotentTerminalStatus(taskId, status);
    }

    private void requireIdempotentTerminalStatus(String taskId, GenerationTaskStatus status) {
        DurableGenerationTaskRecord existing = findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist"));
        if (existing.status() != status) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task terminal status conflict: persisted=" + existing.status().getValue()
                            + ", requested=" + status.getValue());
        }
    }

    /**
 * 查找匹配的{@code Expired}{@code Leases}。
 *
 * @param now 当前时间
 * @param limit 资源上限
 * @return {@code Expired}{@code Leases}集合
 */
    @Override
    public List<GenerationTaskRecoveryCandidate> findExpiredLeases(Instant now, int limit) {
        Objects.requireNonNull(now, "now");
        if (limit <= 0 || limit > MAX_RECOVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("recovery limit must be between 1 and " + MAX_RECOVERY_BATCH_SIZE);
        }
        List<GenerationTask> values = mapper.selectExpiredLeases(toLocal(now), limit);
        if (values == null) return List.of();
        return values.stream().filter(Objects::nonNull).map(this::toRecoveryCandidate).toList();
    }

    /**
 * 返回{@code finalize}{@code Expired}租约。
 *
 * @param candidate 候选
 * @param terminalStatus 待处理的 {@code terminalStatus} 集合
 * @param completedAt 完成时间
 * @param reason 原因
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean finalizeExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                        GenerationTaskStatus terminalStatus,
                                        Instant completedAt,
                                        String reason) {
        Objects.requireNonNull(candidate, "candidate");
        if (!isRecoveryTerminalStatus(terminalStatus)) {
            throw new IllegalArgumentException(
                    "expired task status must be failed, cancelled or deadline_exceeded"
            );
        }
        Objects.requireNonNull(completedAt, "completedAt");
        GenerationFinalizationCommand terminalCommand = terminalCommand(
                candidate.taskId(), candidate.appId(), terminalStatus, reason,
                recoveryEffectFence(candidate));
        return mapper.finalizeExpiredLease(
                candidate.taskId(), candidate.status().getValue(), candidate.version(),
                terminalStatus.getValue(), toLocal(completedAt), normalizeReason(reason),
                GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION,
                GenerationFinalizationCommandCodec.toJson(terminalCommand),
                terminalCommand.executionFence().executionEpoch()
        ) == 1;
    }

    private GenerationFinalizationCommand terminalCommand(String taskId,
                                                            Long appId,
                                                            GenerationTaskStatus status,
                                                            String reason,
                                                            GenerationExecutionFence fence) {
        return GenerationFinalizationCommand.of(
                taskId, appId, fence, status, normalizeReason(reason),
                null, com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality.empty());
    }

    private GenerationExecutionFence unownedEffectFence(GenerationTask task) {
        long currentEpoch = task.getExecutionEpoch() == null ? 0L : task.getExecutionEpoch();
        long effectEpoch = "waiting_approval".equals(task.getStatus())
                ? Math.max(1L, currentEpoch - 1L)
                : Math.max(1L, currentEpoch);
        return new GenerationExecutionFence(task.getTaskId(), "terminal-unowned", effectEpoch);
    }

    private GenerationExecutionFence recoveryEffectFence(GenerationTaskRecoveryCandidate candidate) {
        String owner = candidate.leaseOwner() == null || candidate.leaseOwner().isBlank()
                ? "terminal-recovery" : candidate.leaseOwner();
        return new GenerationExecutionFence(
                candidate.taskId(), owner, Math.max(1L, candidate.executionEpoch()));
    }

    @Override
    public boolean finalizeExpiredPublishedTask(GenerationTaskRecoveryCandidate candidate,
                                                GenerationFinalizationCommand command,
                                                Instant completedAt) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(completedAt, "completedAt");
        GenerationExecutionFence fence = command.executionFence();
        if (command.status() != GenerationTaskStatus.SUCCESS
                || fence == null
                || !candidate.taskId().equals(command.taskId())
                || !Objects.equals(candidate.appId(), command.appId())
                || candidate.executionEpoch() != fence.executionEpoch()) {
            throw new IllegalArgumentException("已发布任务终态意图与恢复候选不一致");
        }
        var quality = command.outcomeQuality() == null
                ? com.rush.rushaicodemother.service.trace.GenerationOutcomeQuality.empty()
                : command.outcomeQuality();
        String payload = GenerationFinalizationCommandCodec.toJson(command);
        if (traceMapper == null) {
            throw new IllegalStateException("已发布任务恢复缺少 Trace 持久化组件");
        }
        GenerationTask task = mapper.selectRuntimeByTaskId(candidate.taskId());
        if (task == null || task.getId() == null || task.getSubmittedAt() == null) {
            return false;
        }
        long durationMs = Math.max(0L, java.time.Duration.between(
                toInstant(task.getSubmittedAt()), completedAt).toMillis());
        return traceMapper.completeRunningTask(
                task.getId(), GenerationTaskStatus.SUCCESS.getValue(), toLocal(completedAt), durationMs,
                normalizeReason(command.reason()), command.memorySummary(), null, 0L,
                quality.thinkingMode(), quality.changedFileCount(), quality.firstBuildPassedValue(),
                quality.repairRounds(), quality.firstPreviewMillis(), quality.failureCategory(),
                quality.reworkedAt(), quality.distilledAt(), candidate.version(), fence.executionEpoch(),
                GenerationFinalizationCommandCodec.CURRENT_SCHEMA_VERSION, payload) == 1;
    }

    /**
 * 返回{@code requeue}{@code Expired}租约。
 *
 * @param candidate 候选
 * @param requeuedAt {@code requeuedAt} 对应的调用参数
 * @param reason 原因
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @Override
    public boolean requeueExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                       Instant requeuedAt,
                                       String reason) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(requeuedAt, "requeuedAt");
        if (candidate.status().isTerminal()) {
            throw new IllegalArgumentException("expired task must be non-terminal");
        }
        return mapper.requeueExpiredLease(
                candidate.taskId(), candidate.status().getValue(), candidate.version(),
                toLocal(requeuedAt), normalizeReason(reason)
        ) == 1;
    }

    /**
 * 查找匹配的{@code Dispatchable}{@code Queued}任务{@code Ids}。
 *
 * @param now 当前时间
 * @param dispatchedBefore {@code dispatchedBefore} 对应的调用参数
 * @param limit 资源上限
 * @return {@code Dispatchable}{@code Queued}任务{@code Ids}集合
 */
    @Override
    public List<String> findDispatchableQueuedTaskIds(Instant now,
                                                       Instant dispatchedBefore,
                                                       int limit) {
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(dispatchedBefore, "dispatchedBefore");
        if (limit <= 0 || limit > MAX_RECOVERY_BATCH_SIZE) {
            throw new IllegalArgumentException("dispatch limit must be between 1 and " + MAX_RECOVERY_BATCH_SIZE);
        }
        List<String> taskIds = mapper.selectDispatchableQueuedTaskIds(
                toLocal(now), toLocal(dispatchedBefore), limit);
        return taskIds == null ? List.of() : taskIds.stream()
                .filter(Objects::nonNull)
                .filter(taskId -> taskId.matches("[A-Za-z0-9_-]{1,128}"))
                .toList();
    }

    /**
 * 记录{@code Dispatch}成功相关指标或状态。
 *
 * @param taskId 任务编号
 * @param dispatchedAt {@code dispatchedAt} 对应的调用参数
 */
    @Override
    public void recordDispatchSuccess(String taskId, Instant dispatchedAt) {
        requireTaskId(taskId);
        Objects.requireNonNull(dispatchedAt, "dispatchedAt");
        mapper.recordDispatchSuccess(taskId, toLocal(dispatchedAt));
    }

    /**
 * 记录{@code Dispatch}失败相关指标或状态。
 *
 * @param taskId 任务编号
 * @param error 错误
 * @param failedAt {@code failedAt} 对应的调用参数
 */
    @Override
    public void recordDispatchFailure(String taskId, String error, Instant failedAt) {
        requireTaskId(taskId);
        Objects.requireNonNull(failedAt, "failedAt");
        String normalized = error == null || error.isBlank() ? "queue_dispatch_failed" : error.trim();
        if (normalized.length() > 1000) {
            normalized = normalized.substring(0, 1000);
        }
        mapper.recordDispatchFailure(taskId, normalized, toLocal(failedAt));
    }

    /** 将当前对象转换为记录。 */
    private DurableGenerationTaskRecord toRecord(GenerationTask entity) {
        GenerationTaskStatus status = GenerationTaskStatus.fromValue(entity.getStatus());
        if (status == null || entity.getTaskId() == null || entity.getAppId() == null
                || entity.getUserId() == null || entity.getTenantId() == null
                || entity.getSubmittedAt() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Generation task runtime data is incomplete");
        }
        return new DurableGenerationTaskRecord(entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                entity.getTenantId(),
                entity.getRoute(), status, entity.getStage(), entity.getStageMessage(),
                toInstant(entity.getSubmittedAt()), toInstant(entity.getDeadlineAt()),
                Integer.valueOf(1).equals(entity.getCancellationRequested()), entity.getCancellationReason(),
                entity.getLeaseOwner(), toInstant(entity.getLeaseUntil()), toInstant(entity.getHeartbeatAt()),
                entity.getAttempt() == null ? 0 : entity.getAttempt(), entity.getVersion() == null ? 0L : entity.getVersion(),
                toInstant(entity.getEndTime()), entity.getErrorMessage());
    }

    /** 校验{@code ate}{@code Duplicate}{@code Identity}是否有效。 */
    private void validateDuplicateIdentity(GenerationTask existing,
                                           GenerationTaskSubmissionRecord task) {
        if (existing == null || !Objects.equals(existing.getAppId(), task.appId())
                || !Objects.equals(existing.getUserId(), task.userId())
                || !Objects.equals(existing.getTenantId(), task.tenantId())
                || !Objects.equals(existing.getIdempotencyKeyHash(), task.idempotencyKeyHash())
                || !Objects.equals(existing.getRequestFingerprint(), task.requestFingerprint())
                || !Objects.equals(existing.getRoute(), task.route())
                || !Integer.valueOf(task.command().schemaVersion()).equals(existing.getRuntimeSchemaVersion())
                || existing.getRuntimePayloadJson() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task id is occupied by a different request");
        }
        GenerationTaskCommand existingCommand;
        try {
            existingCommand = GenerationTaskCommandCodec.fromJson(existing.getRuntimePayloadJson());
        } catch (RuntimeException malformed) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Persisted generation task command is corrupted", malformed);
        }
        if (!existingCommand.equals(task.command())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task id is occupied by a different request");
        }
    }

    /** 将当前对象转换为恢复候选。 */
    private GenerationTaskRecoveryCandidate toRecoveryCandidate(GenerationTask entity) {
        GenerationTaskStatus status = GenerationTaskStatus.fromValue(entity.getStatus());
        if (status == null || status.isTerminal()) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Invalid task recovery candidate status");
        }
        return new GenerationTaskRecoveryCandidate(
                entity.getTaskId(), entity.getAppId(), status, entity.getLeaseOwner(),
                toInstant(entity.getLeaseUntil()), toInstant(entity.getDeadlineAt()),
                Integer.valueOf(1).equals(entity.getCancellationRequested()),
                entity.getCancellationReason(),
                entity.getExecutionEpoch() == null ? 0L : entity.getExecutionEpoch(),
                entity.getVersion() == null ? 0L : entity.getVersion()
        );
    }

    /** 校验并返回有效的{@code Owned}租约。 */
    private GenerationTaskLease requireOwnedLease(String taskId, String owner) {
        GenerationTask entity = mapper.selectOwnedLease(taskId, owner);
        if (entity == null || entity.getLeaseUntil() == null || entity.getExecutionEpoch() == null
                || entity.getExecutionEpoch() <= 0) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "Generation task lease claim could not be reconstructed"
            );
        }
        return new GenerationTaskLease(
                new GenerationExecutionFence(taskId, owner, entity.getExecutionEpoch()),
                toInstant(entity.getLeaseUntil())
        );
    }

    private void requireLeaseArguments(GenerationTaskLease lease, Instant now, Instant until) {
        Objects.requireNonNull(lease, "lease");
        requireLeaseArguments(lease.taskId(), lease.leaseOwner(), now, until);
    }

    /** 校验并返回有效的租约参数。 */
    private void requireLeaseArguments(String taskId, String owner, Instant now, Instant until) {
        requireTaskId(taskId);
        if (owner == null || owner.isBlank()) throw new IllegalArgumentException("leaseOwner cannot be blank");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(until, "leaseUntil");
        if (!until.isAfter(now)) throw new IllegalArgumentException("leaseUntil must be after now");
    }

    private void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("taskId format is invalid");
        }
    }

    private void requireOneRow(int rows, String operation) {
        if (rows != 1) throw new BusinessException(ErrorCode.OPERATION_ERROR,
                operation + " affected unexpected rows: " + rows);
    }

    private boolean isRecoveryTerminalStatus(GenerationTaskStatus status) {
        return status == GenerationTaskStatus.FAILED
                || status == GenerationTaskStatus.CANCELLED
                || status == GenerationTaskStatus.DEADLINE_EXCEEDED;
    }

    private String normalizeReason(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private LocalDateTime toLocal(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, databaseZone);
    }

    private Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.atZone(databaseZone).toInstant();
    }
}
