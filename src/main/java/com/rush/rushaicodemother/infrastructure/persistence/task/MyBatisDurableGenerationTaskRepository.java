package com.rush.rushaicodemother.infrastructure.persistence.task;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.GenerationTaskRuntimeMapper;
import com.rush.rushaicodemother.model.entity.GenerationTask;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
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
        GenerationTask entity = GenerationTask.builder()
                .taskId(task.taskId()).appId(task.appId()).userId(task.userId()).route(task.route())
                .submittedAt(toLocal(task.submittedAt())).deadlineAt(toLocal(task.deadlineAt()))
                .leaseOwner(task.leaseOwner()).leaseUntil(toLocal(task.leaseUntil())).build();
        try {
            requireOneRow(mapper.insertSubmittedTask(entity), "create submitted generation task");
        } catch (DuplicateKeyException duplicate) {
            GenerationTask existing = mapper.selectRuntimeByTaskId(task.taskId());
            if (existing == null || !Objects.equals(existing.getAppId(), task.appId())
                    || !Objects.equals(existing.getUserId(), task.userId())
                    || !Objects.equals(existing.getRoute(), task.route())) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR,
                        "Generation task id is occupied by a different request");
            }
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
    public boolean activate(String taskId, String owner, Instant now, Instant until) {
        requireLeaseArguments(taskId, owner, now, until);
        return mapper.activateOwnedTask(taskId, owner, toLocal(now), toLocal(until)) == 1;
    }

    @Override
    @Transactional
    public GenerationTaskLeaseRenewal renewLease(String taskId, String owner, Instant now, Instant until) {
        requireLeaseArguments(taskId, owner, now, until);
        if (mapper.renewOwnedLease(taskId, owner, toLocal(now), toLocal(until)) != 1) {
            return GenerationTaskLeaseRenewal.lost();
        }
        GenerationTask current = mapper.selectRuntimeByTaskId(taskId);
        return current == null ? GenerationTaskLeaseRenewal.lost() : new GenerationTaskLeaseRenewal(
                true, Integer.valueOf(1).equals(current.getCancellationRequested()),
                current.getCancellationReason());
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
    public void complete(String taskId, GenerationTaskStatus status, String reason,
                         String owner, Instant completedAt) {
        requireTaskId(taskId);
        if (status == null || !status.isTerminal()) throw new IllegalArgumentException("status must be terminal");
        Objects.requireNonNull(completedAt, "completedAt");
        int changed = mapper.completeNonTerminalTask(taskId, status.getValue(), normalizeReason(reason), toLocal(completedAt));
        if (changed == 1) return;
        DurableGenerationTaskRecord existing = findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND_ERROR, "Generation task does not exist"));
        if (existing.status() != status) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    "Generation task terminal status conflict: persisted=" + existing.status().getValue()
                            + ", requested=" + status.getValue());
        }
        if (owner != null && !owner.isBlank()) {
            mapper.clearMatchingTerminalLease(taskId, status.getValue(), owner, toLocal(completedAt));
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

    private DurableGenerationTaskRecord toRecord(GenerationTask entity) {
        GenerationTaskStatus status = GenerationTaskStatus.fromValue(entity.getStatus());
        if (status == null || entity.getTaskId() == null || entity.getAppId() == null
                || entity.getUserId() == null || entity.getSubmittedAt() == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "Generation task runtime data is incomplete");
        }
        return new DurableGenerationTaskRecord(entity.getTaskId(), entity.getAppId(), entity.getUserId(),
                entity.getRoute(), status, entity.getStage(), entity.getStageMessage(),
                toInstant(entity.getSubmittedAt()), toInstant(entity.getDeadlineAt()),
                Integer.valueOf(1).equals(entity.getCancellationRequested()), entity.getCancellationReason(),
                entity.getLeaseOwner(), toInstant(entity.getLeaseUntil()), toInstant(entity.getHeartbeatAt()),
                entity.getAttempt() == null ? 0 : entity.getAttempt(), entity.getVersion() == null ? 0L : entity.getVersion(),
                toInstant(entity.getEndTime()), entity.getErrorMessage());
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
                entity.getVersion() == null ? 0L : entity.getVersion()
        );
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
