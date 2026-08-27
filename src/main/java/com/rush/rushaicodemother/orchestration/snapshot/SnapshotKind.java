package com.rush.rushaicodemother.orchestration.snapshot;

import java.util.Arrays;

/** 快照用途；用途参与 provenance 校验，避免普通快照冒充自动回滚点。 */
public enum SnapshotKind {
    MANUAL("manual"),
    ROLLBACK_POINT("automatic_rollback"),
    PRE_ROLLBACK_BACKUP("pre_rollback_backup"),
    FAILED_GENERATION_BACKUP("failed_generation_backup");

    private final String value;

    SnapshotKind(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static SnapshotKind fromValue(String value) {
        return Arrays.stream(values())
                .filter(candidate -> candidate.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported snapshot kind: " + value));
    }
}
