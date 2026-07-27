package com.rush.rushaicodemother.core.error;

/** Agent 工具调用持续重复且没有取得新进展时的不可重试终止信号。 */
public final class GenerationAgentLoopException extends RuntimeException {

    private final String reasonCode;
    private final String toolName;

    public GenerationAgentLoopException(String reasonCode, String toolName) {
        super("AI 工具调用连续重复且未产生新进展", null, false, false);
        if (reasonCode == null || !reasonCode.matches("[a-z_]{1,64}")) {
            throw new IllegalArgumentException("Agent 循环原因编码无效");
        }
        this.reasonCode = reasonCode;
        this.toolName = toolName == null || toolName.isBlank() ? "unknown" : toolName;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public String toolName() {
        return toolName;
    }
}
