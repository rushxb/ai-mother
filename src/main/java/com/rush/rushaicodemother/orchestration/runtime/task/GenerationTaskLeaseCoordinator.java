package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLease;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 协调当地发电租赁并保持其耐用的围栏时代。
 *
 * <p>A 数据库心跳故障仅在最后确认的本地租约截止日期之前被容忍。
 * 在那一刻之后，即使数据库仍然存在，本地执行上下文也会被取消
 * 不可用，阻止孤立的工作人员无限期地继续。</p>
 */
@Slf4j
@Service
public class GenerationTaskLeaseCoordinator {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseProperties properties;
    private final GenerationTaskLeaseOwnerProvider ownerProvider;
    private final GenerationExecutionContextService executionContextService;
    private final Clock clock;
    private final Map<String, GenerationTaskLease> trackedLeases = new ConcurrentHashMap<>();

    @Autowired
    public GenerationTaskLeaseCoordinator(DurableGenerationTaskRepository repository,
                                          GenerationTaskLeaseProperties properties,
                                          GenerationTaskLeaseOwnerProvider ownerProvider,
                                          GenerationExecutionContextService executionContextService) {
        this(repository, properties, ownerProvider, executionContextService, Clock.systemUTC());
    }

    GenerationTaskLeaseCoordinator(DurableGenerationTaskRepository repository,
                                   GenerationTaskLeaseProperties properties,
                                   GenerationTaskLeaseOwnerProvider ownerProvider,
                                   GenerationExecutionContextService executionContextService,
                                   Clock clock) {
        this.repository = repository;
        this.properties = properties;
        this.ownerProvider = ownerProvider;
        this.executionContextService = executionContextService;
        this.clock = clock;
    }

    public GenerationTaskSubmissionRecord submissionRecord(GenerationTaskCommand command) {
        return submissionRecord(command, GenerationTaskIdempotency.none());
    }

    public GenerationTaskSubmissionRecord submissionRecord(GenerationTaskCommand command,
                                                           GenerationTaskIdempotency idempotency) {
        if (command == null) {
            throw new IllegalArgumentException("generation task command cannot be null");
        }
        if (idempotency == null) {
            throw new IllegalArgumentException("generation task idempotency cannot be null");
        }
        return new GenerationTaskSubmissionRecord(
                command.taskId(), command.appId(), command.userId(), command.tenantId(), command.route(),
                command.submittedAt(), command.deadlineAt(),
                idempotency.keyHash(), idempotency.requestFingerprint(), command
        );
    }

    public Optional<GenerationExecutionFence> reserveQueued(String taskId) {
        Instant now = clock.instant();
        Optional<GenerationTaskLease> claimed = repository.reserveQueued(
                taskId, ownerProvider.ownerId(), now, now.plus(properties.getLeaseDuration()));
        claimed.ifPresent(lease -> trackedLeases.put(taskId, lease));
        return claimed.map(GenerationTaskLease::fence);
    }

    public void activate(GenerationExecutionFence fence) {
        GenerationTaskLease lease = requireTrackedLease(fence);
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        if (repository.activate(lease, now, leaseUntil)) {
            trackedLeases.replace(fence.taskId(), lease, lease.renewedUntil(leaseUntil));
            return;
        }

        trackedLeases.remove(fence.taskId(), lease);
        DurableGenerationTaskRecord current = repository.findByTaskId(fence.taskId())
                .orElseThrow(() -> new GenerationExecutionPolicyException(
                        "generation task does not exist while activating its worker lease"));
        if (current.cancellationRequested()) {
            executionContextService.cancelByTaskId(fence.taskId(), current.cancellationReason());
            throw new GenerationExecutionCancelledException(current.cancellationReason());
        }
        if (current.terminal()) {
            throw new GenerationExecutionPolicyException(
                    "generation task is already terminal, status=" + current.status().getValue());
        }
        executionContextService.cancelByTaskId(fence.taskId(), "worker_lease_lost");
        throw new GenerationExecutionPolicyException("generation task worker lease was lost");
    }

    public void heartbeatTrackedTasks() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        for (Map.Entry<String, GenerationTaskLease> entry : Map.copyOf(trackedLeases).entrySet()) {
            String taskId = entry.getKey();
            GenerationTaskLease lease = entry.getValue();
            if (executionContextService.shouldStop(taskId)) {
                trackedLeases.remove(taskId, lease);
                log.warn("Stopped renewing generation task lease after local cancellation or deadline, taskId: {}",
                        taskId);
                continue;
            }
            try {
                GenerationTaskLeaseRenewal renewal = repository.renewLease(lease, now, leaseUntil);
                if (!renewal.renewed()) {
                    trackedLeases.remove(taskId, lease);
                    executionContextService.cancelByTaskId(taskId, "worker_lease_lost");
                    log.warn("Generation task lease was lost, taskId: {}", taskId);
                    continue;
                }
                trackedLeases.replace(taskId, lease, renewal.lease());
                if (renewal.cancellationRequested()) {
                    executionContextService.cancelByTaskId(taskId, renewal.cancellationReason());
                }
            } catch (RuntimeException heartbeatFailure) {
                if (lease.expiredAt(now)) {
                    trackedLeases.remove(taskId, lease);
                    executionContextService.cancelByTaskId(taskId, "worker_lease_expired_locally");
                }
                log.error("Generation task heartbeat failed, taskId: {}",
                        taskId, LogExceptionSanitizer.sanitize(heartbeatFailure));
            }
        }
    }

    public void release(GenerationExecutionFence fence) {
        if (fence == null) {
            return;
        }
        GenerationTaskLease lease = trackedLeases.get(fence.taskId());
        if (lease != null && lease.fence().equals(fence)) {
            trackedLeases.remove(fence.taskId(), lease);
        }
    }

    public boolean releaseClaimToQueue(GenerationExecutionFence fence, String reason) {
        GenerationTaskLease lease = requireTrackedLease(fence);
        boolean released = repository.releaseClaimToQueue(lease, clock.instant(), reason);
        if (released) {
            trackedLeases.remove(fence.taskId(), lease);
        }
        return released;
    }

    public boolean suspendForApproval(GenerationExecutionFence fence, String stageMessage) {
        GenerationTaskLease lease = requireTrackedLease(fence);
        boolean suspended = repository.suspendForApproval(lease, stageMessage, clock.instant());
        if (suspended) {
            trackedLeases.remove(fence.taskId(), lease);
        }
        return suspended;
    }

    public Optional<GenerationExecutionFence> requeueAfterApproval(String taskId) {
        Instant now = clock.instant();
        Optional<GenerationTaskLease> claimed = repository.requeueAfterApproval(
                taskId, ownerProvider.ownerId(), now, now.plus(properties.getLeaseDuration()));
        claimed.ifPresent(lease -> trackedLeases.put(taskId, lease));
        return claimed.map(GenerationTaskLease::fence);
    }

    public boolean restoreWaitingAfterDispatchFailure(GenerationExecutionFence fence,
                                                      String stageMessage) {
        GenerationTaskLease lease = requireTrackedLease(fence);
        boolean restored = repository.restoreWaitingAfterDispatchFailure(
                lease, stageMessage, clock.instant());
        if (restored) {
            trackedLeases.remove(fence.taskId(), lease);
        }
        return restored;
    }

    /**
     * 在简短的发布关键部分之前和期间更新租约。
     * 如果取消、截止日期或隔离状态发生变化，出版物必须无法关闭。
     */
    public void renewForCriticalSection(GenerationExecutionFence fence) {
        GenerationTaskLease lease = requireTrackedLease(fence);
        if (executionContextService.shouldStop(fence.taskId())) {
            throw new GenerationExecutionPolicyException(
                    "generation task cannot publish after cancellation or deadline");
        }
        Instant now = clock.instant();
        GenerationTaskLeaseRenewal renewal = repository.renewLease(
                lease, now, now.plus(properties.getLeaseDuration()));
        if (!renewal.renewed() || renewal.cancellationRequested()) {
            trackedLeases.remove(fence.taskId(), lease);
            String reason = renewal.cancellationRequested()
                    ? renewal.cancellationReason()
                    : "worker_lease_lost_during_publication";
            executionContextService.cancelByTaskId(fence.taskId(),
                    reason == null || reason.isBlank() ? "worker_lease_lost_during_publication" : reason);
            throw new GenerationExecutionPolicyException(
                    "generation task lease is not valid for publication");
        }
        trackedLeases.replace(fence.taskId(), lease, renewal.lease());
    }

    public void completeOwned(GenerationExecutionFence fence,
                              GenerationTaskStatus status,
                              String reason,
                              Instant completedAt) {
        repository.completeOwned(requireTrackedLease(fence), status, reason, completedAt);
    }

    public String ownerId() {
        return ownerProvider.ownerId();
    }

    int trackedTaskCount() {
        return trackedLeases.size();
    }

    private GenerationTaskLease requireTrackedLease(GenerationExecutionFence fence) {
        if (fence == null) {
            throw new IllegalArgumentException("generation execution fence cannot be null");
        }
        GenerationTaskLease lease = trackedLeases.get(fence.taskId());
        if (lease == null || !lease.fence().equals(fence)) {
            executionContextService.cancelByTaskId(fence.taskId(), "worker_lease_lost");
            throw new GenerationExecutionPolicyException(
                    "generation task worker lease is no longer current");
        }
        return lease;
    }
}
