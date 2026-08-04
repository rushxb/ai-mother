package com.rush.rushaicodemother.orchestration.attempt.completion;

import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;

/** 完成证据不足时使用的失败关闭异常。 */
public class GenerationCompletionEvidenceException extends GenerationExecutionPolicyException {

    private final GenerationCompletionDecision decision;

    public GenerationCompletionEvidenceException(GenerationCompletionDecision decision) {
        super(decision == null ? "生成任务完成证据不足" : decision.summary());
        this.decision = decision;
    }

    public GenerationCompletionDecision decision() {
        return decision;
    }
}
