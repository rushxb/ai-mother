package com.rush.rushaicodemother.orchestration.tool;

/**
 * 工具审批状态的可选类型。
 */
public enum ToolApprovalStatus {
    PENDING("pending"),
    APPROVED("approved"),
    REJECTED("rejected"),
    EXECUTING("executing"),
    CONSUMED("consumed"),
    EXPIRED("expired");

    private final String value;

    ToolApprovalStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static ToolApprovalStatus fromValue(String value) {
        for (ToolApprovalStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        return null;
    }
}
