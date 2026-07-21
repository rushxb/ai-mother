package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLease;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLoadSnapshot;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommandCodec;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** MyBatis adapter for the durable generation-task runtime port. */
@Repository
@RequiredArgsConstructor
public class MyBatisDurableGenerationTaskRepository implements DurableGenerationTaskRepository {

    private static final int MAX_RECOVERY_BATCH_SIZE = 500;

    private final GenerationTaskRuntimeMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

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
        GenerationTask entity = GenerationTask.builder()
                .taskId(task.taskId()).appId(task.appId()).userId(task.userId())
                .tenantId(task.tenantId())
                .idempotencyKeyHash(task.idempotencyKeyHash())
                .requestFingerprint(task.requestFingerprint())
                .route(task.route())
                .submittedAt(toLocal(task.submittedAt())).deadlineAt(toLocal(task.deadlineAt()))
                .runtimeSchemaVersion(task.command().schemaVersion())
                .runtimePayloadJson(GenerationTaskCommandCodec.toJson(task.command()))
                .build();
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

    @Override
    public Optional<DurableGenerationTaskRecord> findByTaskId(String taskId) {
        requireTaskId(taskId);
        return Optional.ofNullable(mapper.selectRuntimeByTaskId(taskId)).map(this::toRecord);
    }

    @Override
    public Optional<DurableGenerationTaskRecord> findLatestNonTerminalByAppId(Long appId) {
        if (appId == null || appId <= 0) throw new IllegalArgumentException("appId must be positive");
        return Optional.ofNullable(mapper.selectLatestNonTerminalByAppId(appId)).map(this::toRecord);
    }

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
    public boolean isCurrentFence(GenerationExecutionFence fence, Instant now) {
        Objects.requireNonNull(fence, "fence");
        Objects.requireNonNull(now, "now");
        return mapper.countCurrentExecutionFence(
                fence.taskId(), fence.leaseOwner(), fence.executionEpoch(), toLocal(now)) == 1;
    }

    @Override
    public GenerationTaskLoadSnapshot loadCurrentLoad() {
        return new GenerationTaskLoadSnapshot(
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.QUEUED.getValue()),
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.RUNNING.getValue()),
                mapper.countRuntimeTasksByStatus(GenerationTaskStatus.WAITING_APPROVAL.getValue())
        );
    }

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

    @Override
    public boolean activate(GenerationTaskLease lease, Instant now, Instant until) {
        requireLeaseArguments(lease, now, until);
        return mapper.activateOwnedTask(
                lease.taskId(), lease.leaseOwner(), lease.executionEpoch(),
                toLocal(now), toLocal(until)) == 1;
    }

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

    @Override
    public boolean requestCancellation(String taskId, String reason, Instant requestedAt) {
        requireTaskId(taskId);
        Objects.requireNonNull(requestedAt, "requestedAt");
        String normalized = reason == null || reason.isBlank() ? "user_requested" : reason.trim();
        if (mapper.requestCancellation(taskId, normalized, toLocal(requestedAt)) == 1) return true;
        return findByTaskId(taskId).map(DurableGenerationTaskRecord::cancellationRequested).orElse(false);
    }

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

    @Override
    @Transactional
    public void completeUnowned(String taskId,
                                GenerationTaskStatus status,
                                String reason,
                                Instant completedAt) {
        requireTaskId(taskId);
        if (status == null || !status.isTerminal()) throw new IllegalArgumentException("status must be terminal");
        Objects.requireNonNull(completedAt, "completedAt");
        int changed = mapper.completeUnownedTask(
                taskId, status.getValue(), normalizeReason(reason), toLocal(completedAt));
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
        return mapper.finalizeExpiredLease(
                candidate.taskId(), candidate.status().getValue(), candidate.version(),
                terminalStatus.getValue(), toLocal(completedAt), normalizeReason(reason)
        ) == 1;
    }

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

    @Override
    public void recordDispatchSuccess(String taskId, Instant dispatchedAt) {
        requireTaskId(taskId);
        Objects.requireNonNull(dispatchedAt, "dispatchedAt");
        mapper.recordDispatchSuccess(taskId, toLocal(dispatchedAt));
    }

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
