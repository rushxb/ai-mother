package com.rush.rushaicodemother.orchestration.tool;

/** 针对违反中央功能策略的 AI 工具调用的故障关闭信号。 */
public final class ToolPolicyViolationException extends RuntimeException {

    private final String violationCode;

    public ToolPolicyViolationException(String violationCode) {
        super("AI tool invocation rejected by central policy", null, false, false);
        if (violationCode == null || !violationCode.matches("[a-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("tool policy violation code is invalid");
        }
        this.violationCode = violationCode;
    }

    public String violationCode() {
        return violationCode;
    }
}
