package com.rush.rushaicodemother.orchestration.finalization;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.GenerationAppStateService;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskRuntimeLifecycleService;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** 只负责生成终态的数据库事务，不承载事件、记忆或运行时资源清理。 */
@Service
@RequiredArgsConstructor
class GenerationTaskFinalizationTransaction {

    private final GenerationTaskLifecycleService taskLifecycleService;
    private final GenerationTaskRuntimeLifecycleService runtimeLifecycleService;
    private final DurableGenerationTaskRepository taskRepository;
    private final GenerationAppStateService appStateService;

    @Transactional(rollbackFor = Exception.class)
    public void finalizeManaged(GenerationFinalizationCommand command) {
        if (command.executionFence() != null) {
            taskRepository.prepareFinalizationIntent(command, Instant.now());
        }
        taskLifecycleService.finalizeGeneration(
                command.taskId(),
                command.appId(),
                command.executionFence() == null ? null : command.executionFence().executionEpoch(),
                command.status(),
                command.reason(),
                command.memorySummary(),
                command.outcomeQuality()
        );
        if (command.executionFence() != null) {
            // Trace 与 durable runtime 共用 generation_task；先写完整业务终态，
            // 再由 runtime 契约幂等确认，避免先置终态后丢失质量证据。
            runtimeLifecycleService.persistOwnedCompletion(
                    command.executionFence(), command.status(), command.reason());
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeOwnedRuntime(GenerationFinalizationCommand command) {
        taskRepository.prepareFinalizationIntent(command, Instant.now());
        runtimeLifecycleService.persistOwnedCompletion(
                command.executionFence(), command.status(), command.reason());
    }

    @Transactional(rollbackFor = Exception.class)
    public void finalizeUnownedRuntime(String taskId,
                                       GenerationTaskStatus status,
                                       String reason) {
        Long appId = taskRepository.findByTaskId(taskId)
                .map(task -> task.appId())
                .orElse(null);
        if (appId != null) {
            appStateService.lockGenerationState(appId);
        }
        runtimeLifecycleService.persistUnownedCompletion(taskId, status, reason);
        if (appId != null) {
            appStateService.releaseTerminalGenerationState(appId, taskId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeExpiredLease(GenerationTaskRecoveryCandidate candidate,
                                        GenerationTaskStatus status,
                                        Instant completedAt,
                                        String reason) {
        appStateService.lockGenerationState(candidate.appId());
        if (!taskRepository.finalizeExpiredLease(candidate, status, completedAt, reason)) {
            return false;
        }
        appStateService.releaseOwnedGenerationState(
                candidate.appId(), candidate.taskId(), candidate.executionEpoch());
        return true;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean finalizeExpiredPublishedTask(GenerationTaskRecoveryCandidate candidate,
                                                GenerationFinalizationCommand command,
                                                Instant completedAt) {
        appStateService.lockGenerationState(candidate.appId());
        if (!taskRepository.finalizeExpiredPublishedTask(candidate, command, completedAt)) {
            return false;
        }
        appStateService.releaseOwnedGenerationState(
                candidate.appId(), candidate.taskId(), candidate.executionEpoch());
        return true;
    }
}
