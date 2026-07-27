package com.rush.rushaicodemother.orchestration.tool;

import com.rush.rushaicodemother.config.AiToolApprovalProperties;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.DurableGenerationTaskRepository;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/** 以原子方式将孤立的持久工具延续转回可恢复的等待工作。 */
@Service
@RequiredArgsConstructor
public class ToolExecutionRecoveryService {

    private final ToolApprovalRepository approvalRepository;
    private final DurableGenerationTaskRepository taskRepository;
    private final AiToolApprovalProperties properties;

    @Transactional
    public Optional<ToolApprovalRecord> recover(
            GenerationTaskRecoveryCandidate candidate,
            Instant recoveredAt
    ) {
        ToolApprovalRecord approval = approvalRepository
                .findRecoverableExecution(candidate.taskId())
                .orElse(null);
        if (approval == null || approval.invocationCheckpoint() == null) {
            return Optional.empty();
        }
        if (approval.status() == ToolApprovalStatus.EXECUTING) {
            if (approval.executionAttempt() >= properties.getMaxExecutionAttempts()) {
                return Optional.empty();
            }
            if (!approvalRepository.resetStaleExecution(
                    approval.taskId(), approval.action(), approval.approvalId(), approval.version())) {
                throw new IllegalStateException("stale tool execution recovery lost approval ownership");
            }
            approval = approvalRepository.find(
                            approval.taskId(), approval.action(), approval.approvalId())
                    .orElseThrow(() -> new IllegalStateException(
                            "recovered tool approval disappeared"));
        }
        if (approval.status() != ToolApprovalStatus.APPROVED
                && approval.status() != ToolApprovalStatus.CONSUMED) {
            return Optional.empty();
        }
        if (!taskRepository.restoreWaitingAfterStaleToolExecution(
                candidate, "tool_execution_recovery", recoveredAt)) {
            throw new IllegalStateException("stale tool execution recovery lost task ownership");
        }
        return Optional.of(approval);
    }
}
