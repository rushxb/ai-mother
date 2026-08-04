package com.rush.rushaicodemother.orchestration.attempt.completion;

/** 生成任务进入成功终态前可被机器校验的证据类型。 */
public enum GenerationCompletionEvidenceType {

    INTENT_COVERAGE,
    WORKSPACE_CHANGE,
    NO_CHANGE_JUSTIFICATION,
    FAST_VALIDATION,
    BUILD_VALIDATION,
    EXPERT_VALIDATION
}
