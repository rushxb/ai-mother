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

    /**
 * 校验并提交当前请求。
 *
 * @param command 命令
 */
    public void submit(GenerationTaskCommand command) {
        submit(command, GenerationTaskIdempotency.none());
    }

    /**
 * 校验并提交当前请求。
 *
 * @param command 命令
 * @param idempotency {@code idempotency} 对应的调用参数
 */
    public void submit(GenerationTaskCommand command, GenerationTaskIdempotency idempotency) {
        repository.createSubmitted(leaseCoordinator.submissionRecord(command, idempotency));
    }

    /**
 * 处理{@code activate}。
 *
 * @param fence 围栏
 */
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

    /**
 * 返回请求{@code Cancellation}。
 *
 * @param taskId 任务编号
 * @param reason 原因
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean requestCancellation(String taskId, String reason) {
        return repository.requestCancellation(taskId, reason, clock.instant());
    }

    /**
 * 查找匹配的按任务编号。
 *
 * @param taskId 任务编号
 * @return 可选的按任务编号；不存在时返回空值
 */
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

    /**
 * 完成{@code Owned}并持久化终态。
 *
 * @param fence 围栏
 * @param status 目标状态
 * @param reason 原因
 */
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

    /**
 * 将无主任务更新为指定终态。
 *
 * @param taskId 任务编号
 * @param status 目标状态
 * @param reason 原因
 */
    public void completeUnowned(String taskId, GenerationTaskStatus status, String reason) {
        DurableGenerationTaskRecord task = safeFind(taskId);
        Instant completedAt = clock.instant();
        repository.completeUnowned(taskId, status, reason, completedAt);
        recordUserWait(task, status, completedAt);
        if (userCreditService != null) {
            userCreditService.chargeGenerationTask(taskId);
        }
    }

    /** 返回安全{@code Find}。 */
    private DurableGenerationTaskRecord safeFind(String taskId) {
        try {
            return repository.findByTaskId(taskId).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 记录用户{@code Wait}相关指标或状态。 */
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

    /** 记录{@code Initial}持久{@code Queue}{@code Wait}相关指标或状态。 */
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
