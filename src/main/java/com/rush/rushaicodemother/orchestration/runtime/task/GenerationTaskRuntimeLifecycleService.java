package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import com.rush.rushaicodemother.monitor.GenerationPerformanceMonitorService;
import com.rush.rushaicodemother.monitor.span.GenerationSpanCategory;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRecord;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskCommand;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.service.UserCreditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** 用于持久提交、激活、取消和终端清理的应用程序接缝。 */
@Service
public class GenerationTaskRuntimeLifecycleService {

    private final DurableGenerationTaskRepository repository;
    private final GenerationTaskLeaseCoordinator leaseCoordinator;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final GenerationPerformanceMonitorService performanceMonitorService;
    private final UserCreditService userCreditService;
    private final Clock clock;

    @Autowired
    public GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                                 GenerationTaskLeaseCoordinator leaseCoordinator,
                                                 GenerationOrchestrationMetricsCollector metricsCollector,
                                                 GenerationPerformanceMonitorService performanceMonitorService,
                                                 UserCreditService userCreditService) {
        this(repository, leaseCoordinator, metricsCollector, performanceMonitorService,
                userCreditService, Clock.systemUTC());
    }

    GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                          GenerationTaskLeaseCoordinator leaseCoordinator,
                                          Clock clock) {
        this(repository, leaseCoordinator, null, null, null, clock);
    }

    GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                          GenerationTaskLeaseCoordinator leaseCoordinator,
                                          GenerationOrchestrationMetricsCollector metricsCollector,
                                          Clock clock) {
        this(repository, leaseCoordinator, metricsCollector, null, null, clock);
    }

    GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                          GenerationTaskLeaseCoordinator leaseCoordinator,
                                           GenerationOrchestrationMetricsCollector metricsCollector,
                                           GenerationPerformanceMonitorService performanceMonitorService,
                                           Clock clock) {
        this(repository, leaseCoordinator, metricsCollector, performanceMonitorService, null, clock);
    }

    GenerationTaskRuntimeLifecycleService(DurableGenerationTaskRepository repository,
                                           GenerationTaskLeaseCoordinator leaseCoordinator,
                                           GenerationOrchestrationMetricsCollector metricsCollector,
                                           GenerationPerformanceMonitorService performanceMonitorService,
                                           UserCreditService userCreditService,
                                           Clock clock) {
        this.repository = repository;
        this.leaseCoordinator = leaseCoordinator;
        this.metricsCollector = metricsCollector;
        this.performanceMonitorService = performanceMonitorService;
        this.userCreditService = userCreditService;
        this.clock = clock;
    }

    public void submit(GenerationTaskCommand command) {
        submit(command, GenerationTaskIdempotency.none());
    }

    public void submit(GenerationTaskCommand command, GenerationTaskIdempotency idempotency) {
        repository.createSubmitted(leaseCoordinator.submissionRecord(command, idempotency));
    }

    public void activate(GenerationExecutionFence fence) {
        DurableGenerationTaskRecord queuedTask = safeFind(fence.taskId());
        leaseCoordinator.activate(fence);
        recordInitialDurableQueueWait(queuedTask, clock.instant());
    }

    public Optional<GenerationExecutionFence> reserveQueued(String taskId) {
        return leaseCoordinator.reserveQueued(taskId);
    }

    public boolean releaseClaimToQueue(GenerationExecutionFence fence, String reason) {
        return leaseCoordinator.releaseClaimToQueue(fence, reason);
    }

    public boolean requestCancellation(String taskId, String reason) {
        return repository.requestCancellation(taskId, reason, clock.instant());
    }

    public Optional<DurableGenerationTaskRecord> findByTaskId(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return repository.findByTaskId(taskId);
    }

    public boolean suspendForApproval(GenerationExecutionFence fence, String stageMessage) {
        return leaseCoordinator.suspendForApproval(fence, stageMessage);
    }

    public Optional<GenerationExecutionFence> requeueAfterApproval(String taskId) {
        return leaseCoordinator.requeueAfterApproval(taskId);
    }

    public boolean restoreWaitingAfterDispatchFailure(GenerationExecutionFence fence,
                                                      String stageMessage) {
        return leaseCoordinator.restoreWaitingAfterDispatchFailure(fence, stageMessage);
    }

    public void renewForCriticalSection(GenerationExecutionFence fence) {
        leaseCoordinator.renewForCriticalSection(fence);
    }

    public void completeOwned(GenerationExecutionFence fence,
                              GenerationTaskStatus status,
                              String reason) {
        DurableGenerationTaskRecord task = safeFind(fence.taskId());
        Instant completedAt = clock.instant();
        try {
            leaseCoordinator.completeOwned(fence, status, reason, completedAt);
            recordUserWait(task, status, completedAt);
            if (userCreditService != null) {
                userCreditService.chargeGenerationTask(fence.taskId());
            }
        } finally {
            leaseCoordinator.release(fence);
        }
    }

    public void completeUnowned(String taskId, GenerationTaskStatus status, String reason) {
        DurableGenerationTaskRecord task = safeFind(taskId);
        Instant completedAt = clock.instant();
        repository.completeUnowned(taskId, status, reason, completedAt);
        recordUserWait(task, status, completedAt);
        if (userCreditService != null) {
            userCreditService.chargeGenerationTask(taskId);
        }
    }

    private DurableGenerationTaskRecord safeFind(String taskId) {
        try {
            return repository.findByTaskId(taskId).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void recordUserWait(DurableGenerationTaskRecord task,
                                GenerationTaskStatus status,
                                Instant completedAt) {
        if (metricsCollector == null || task == null || task.submittedAt() == null || completedAt == null) {
            return;
        }
        metricsCollector.recordUserWaitDuration(
                task.route(),
                "unknown",
                status == null ? "unknown" : status.getValue(),
                Duration.between(task.submittedAt(), completedAt)
        );
        boolean breached = task.deadlineAt() != null && !completedAt.isBefore(task.deadlineAt());
        metricsCollector.recordSlaOutcome(
                task.route(),
                "total",
                breached ? "breached" : "met",
                breached ? "deadline_exceeded" : "within_deadline"
        );
    }

    private void recordInitialDurableQueueWait(DurableGenerationTaskRecord task, Instant activatedAt) {
        if (performanceMonitorService == null || task == null || task.attempt() > 0
                || task.submittedAt() == null || activatedAt == null) {
            return;
        }
        Duration duration = activatedAt.isBefore(task.submittedAt())
                ? Duration.ZERO
                : Duration.between(task.submittedAt(), activatedAt);
        performanceMonitorService.recordSpan(
                task.taskId(),
                "durable_queue_wait",
                GenerationSpanCategory.QUEUE,
                "success",
                duration,
                "attempt=0"
        );
    }
}
