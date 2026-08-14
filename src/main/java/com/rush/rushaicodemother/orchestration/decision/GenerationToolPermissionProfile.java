package com.rush.rushaicodemother.orchestration.decision;

/**
 * 场景级工具权限边界。
 *
 * <p>次数预算由执行计划决定；本类型只冻结是否允许副作用以及写入时的安全下限。</p>
 */
public enum GenerationToolPermissionProfile {
    READ_ONLY(false, false, false),
    WRITE_FENCED(true, true, true);

    private final boolean writeAllowed;
    private final boolean writeFenceRequired;
    private final boolean destructiveApprovalRequired;

    GenerationToolPermissionProfile(boolean writeAllowed,
                                    boolean writeFenceRequired,
                                    boolean destructiveApprovalRequired) {
        this.writeAllowed = writeAllowed;
        this.writeFenceRequired = writeFenceRequired;
        this.destructiveApprovalRequired = destructiveApprovalRequired;
    }

    public boolean writeAllowed() {
        return writeAllowed;
    }

    public boolean writeFenceRequired() {
        return writeFenceRequired;
    }

    public boolean destructiveApprovalRequired() {
        return destructiveApprovalRequired;
    }
}
