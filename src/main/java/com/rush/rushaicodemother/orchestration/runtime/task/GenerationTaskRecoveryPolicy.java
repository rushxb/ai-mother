package com.rush.rushaicodemother.orchestration.runtime.task;

import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import com.rush.rushaicodemother.orchestration.runtime.task.persistence.GenerationTaskRecoveryCandidate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Objects;

/**
 * 选择无法从检查点恢复的过期租约终态。
 * 取消优先，因为它是明确持久化的用户意图；已过的绝对期限单独分类。
 */
@Component
public class GenerationTaskRecoveryPolicy {

    static final String DEFAULT_CANCELLATION_REASON = "user_requested";
    static final String DEADLINE_EXCEEDED_REASON = "task_deadline_exceeded";
    static final String ORPHAN_FAILURE_REASON = "worker_lease_expired_non_recoverable";

    /**
 * 根据输入信号确定生成任务恢复策略。
 *
 * @param candidate 候选
 * @param now 当前时间
 * @return 生成任务恢复策略
 */
    public GenerationTaskRecoveryDecision decide(GenerationTaskRecoveryCandidate candidate, Instant now) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(now, "now");

        if (candidate.cancellationRequested()) {
            return new GenerationTaskRecoveryDecision(
                    GenerationTaskStatus.CANCELLED,
                    normalizeReason(candidate.cancellationReason(), DEFAULT_CANCELLATION_REASON)
            );
        }
        if (candidate.deadlineAt() != null && !candidate.deadlineAt().isAfter(now)) {
            return new GenerationTaskRecoveryDecision(
                    GenerationTaskStatus.DEADLINE_EXCEEDED, DEADLINE_EXCEEDED_REASON
            );
        }
        return new GenerationTaskRecoveryDecision(
                GenerationTaskStatus.FAILED, ORPHAN_FAILURE_REASON
        );
    }

    private String normalizeReason(String reason, String fallback) {
        return reason == null || reason.isBlank() ? fallback : reason.trim();
    }
}
