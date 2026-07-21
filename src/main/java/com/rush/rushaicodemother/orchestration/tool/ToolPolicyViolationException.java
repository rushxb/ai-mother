package com.rush.rushaicodemother.orchestration.tool;

/** Fail-closed signal for an AI tool invocation that violates the central capability policy. */
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
