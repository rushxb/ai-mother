package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 生成任务唯一终态入口。
 *
 * <p>数据库终态提交完成后才释放进程内租约并记录终态指标，避免事务回滚时提前丢失执行所有权。</p>
 */
@Service
@Slf4j
public class GenerationTaskFinalizer {

    private final GenerationTaskFinalizationTransaction transaction;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    public GenerationTaskFinalizer(
            GenerationTaskFinalizationTransaction transaction,
            GenerationTaskRuntimeLifecycleService runtimeLifecycleService) {
        this.transaction = transaction;
        this.runtimeLifecycleService = runtimeLifecycleService;
    }

    public void finalizeManaged(GenerationFinalizationCommand command) {
        transaction.finalizeManaged(command);
        completePostCommit(command.taskId(), command.status(), command.executionFence());
    }

    public void finalizeOwnedRuntime(GenerationFinalizationCommand command) {
        if (command.executionFence() == null) {
            throw new IllegalArgumentException("运行时有主任务必须提供执行围栏");
        }
        transaction.finalizeOwnedRuntime(command);
        completePostCommit(command.taskId(), command.status(), command.executionFence());
    }

    public void finalizeUnownedRuntime(String taskId,
                                       GenerationTaskStatus status,
                                       String reason) {
        transaction.finalizeUnownedRuntime(taskId, status, reason);
        recordTerminalMetricSafely(taskId, status);
    }

    public boolean finalizeExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                        GenerationTaskStatus status,
                                        Instant completedAt,
                                        String reason) {
        boolean finalized = transaction.finalizeExpiredLease(candidate, status, completedAt, reason);
        if (finalized) {
            completePostCommit(candidate.taskId(), status, fenceOf(candidate));
        }
        return finalized;
    }

    public boolean finalizeExpiredPublishedTask(GenerationTaskRecoveryCandidate candidate,
                                                GenerationFinalizationCommand command,
                                                Instant completedAt) {
        boolean finalized = transaction.finalizeExpiredPublishedTask(candidate, command, completedAt);
        if (finalized) {
            completePostCommit(candidate.taskId(), command.status(), fenceOf(candidate));
        }
        return finalized;
    }

    private void releaseFence(GenerationExecutionFence fence) {
        if (fence != null) {
            runtimeLifecycleService.releaseTerminalOwnership(fence);
        }
    }

    private void completePostCommit(String taskId,
                                    GenerationTaskStatus status,
                                    GenerationExecutionFence fence) {
        try {
            recordTerminalMetricSafely(taskId, status);
        } finally {
            releaseFence(fence);
        }
    }

    private GenerationExecutionFence fenceOf(GenerationTaskRecoveryCandidate candidate) {
        if (candidate == null || candidate.leaseOwner() == null || candidate.leaseOwner().isBlank()) {
            return null;
        }
        return new GenerationExecutionFence(
                candidate.taskId(), candidate.leaseOwner(), candidate.executionEpoch());
    }

    private void recordTerminalMetricSafely(String taskId, GenerationTaskStatus status) {
        try {
            runtimeLifecycleService.recordTerminalCommit(taskId, status);
        } catch (RuntimeException metricFailure) {
            log.warn("生成任务终态指标记录失败，终态不受影响，taskId: {}",
                    taskId, LogExceptionSanitizer.sanitize(metricFailure));
        }
    }
}
