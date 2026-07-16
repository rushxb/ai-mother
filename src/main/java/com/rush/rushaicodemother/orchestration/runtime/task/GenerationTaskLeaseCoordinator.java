package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionCancelledException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskLeaseRenewal;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskSubmissionRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates all local queued/running leases through one bounded maintenance loop.
 * No task owns a scheduler or platform thread.
 */
@Slf4j
@Service
public class GenerationTaskLeaseCoordinator {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseProperties properties;
    private final GenerationTaskLeaseOwnerProvider ownerProvider;
    private final GenerationExecutionContextService executionContextService;
    private final Clock clock;
    private final Set<String> trackedTaskIds = ConcurrentHashMap.newKeySet();

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

    public GenerationTaskSubmissionRecord submissionRecord(GenerationTaskExecution execution, String route) {
        Instant submittedAt = execution.submittedAt();
        return new GenerationTaskSubmissionRecord(
                execution.taskId(), execution.executionContext().appId(), execution.executionContext().userId(),
                route, submittedAt, execution.executionContext().deadlineAt(), ownerProvider.ownerId(),
                submittedAt.plus(properties.getLeaseDuration())
        );
    }

    public void trackSubmitted(String taskId) {
        trackedTaskIds.add(taskId);
    }

    public void activate(String taskId) {
        Instant now = clock.instant();
        if (repository.activate(taskId, ownerProvider.ownerId(), now, now.plus(properties.getLeaseDuration()))) {
            return;
        }
        DurableGenerationTaskRecord current = repository.findByTaskId(taskId)
                .orElseThrow(() -> new GenerationExecutionPolicyException("生成任务不存在，无法获取 worker lease"));
        if (current.cancellationRequested()) {
            executionContextService.cancelByTaskId(taskId, current.cancellationReason());
            throw new GenerationExecutionCancelledException(current.cancellationReason());
        }
        if (current.terminal()) {
            throw new GenerationExecutionPolicyException(
                    "生成任务已进入终态，status=" + current.status().getValue());
        }
        executionContextService.cancelByTaskId(taskId, "worker_lease_lost");
        throw new GenerationExecutionPolicyException("生成任务 worker lease 已丢失");
    }

    public void heartbeatTrackedTasks() {
        Instant now = clock.instant();
        Instant leaseUntil = now.plus(properties.getLeaseDuration());
        for (String taskId : Set.copyOf(trackedTaskIds)) {
            try {
                GenerationTaskLeaseRenewal renewal = repository.renewLease(
                        taskId, ownerProvider.ownerId(), now, leaseUntil);
                if (!renewal.renewed()) {
                    trackedTaskIds.remove(taskId);
                    executionContextService.cancelByTaskId(taskId, "worker_lease_lost");
                    log.warn("Generation task lease was lost, taskId: {}", taskId);
                } else if (renewal.cancellationRequested()) {
                    executionContextService.cancelByTaskId(taskId, renewal.cancellationReason());
                }
            } catch (RuntimeException heartbeatFailure) {
                // Keep tracking after a transient database failure. The bounded lease will prevent split-brain work.
                log.error("Generation task heartbeat failed, taskId: {}", taskId, heartbeatFailure);
            }
        }
    }

    public void release(String taskId) {
        trackedTaskIds.remove(taskId);
    }

    public String ownerId() {
        return ownerProvider.ownerId();
    }

    int trackedTaskCount() {
        return trackedTaskIds.size();
    }
}
