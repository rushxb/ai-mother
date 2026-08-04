package com.rush.rushaicodemother.orchestration.attempt.completion;

/** 完成判定缺失项，code 用于日志和测试，message 用于中文诊断。 */
public enum GenerationCompletionRequirement {

    INTENT_COVERAGE("completion_intent_evidence_missing", "缺少意图覆盖证据"),
    WORKSPACE_RESULT("completion_workspace_evidence_missing", "缺少有效工作区变更或无需修改证明"),
    FAST_VALIDATION("completion_fast_validation_missing", "缺少快速校验证据"),
    BUILD_VALIDATION("completion_build_validation_missing", "缺少构建校验证据"),
    EXPERT_VALIDATION("completion_expert_validation_missing", "缺少专家级校验证据"),
    RUNTIME_OWNERSHIP("completion_runtime_ownership_invalid", "任务已取消、等待审批或执行租约已失效");

    private final String code;
    private final String message;

    GenerationCompletionRequirement(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
