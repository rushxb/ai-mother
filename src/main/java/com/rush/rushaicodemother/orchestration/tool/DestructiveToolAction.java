package com.rush.rushaicodemother.orchestration.tool;

import java.util.Arrays;

/**
 * Destructive工具Action的可选类型。
 */
public enum DestructiveToolAction {
    SNAPSHOT_ROLLBACK("rollbackSnapshot"),
    SNAPSHOT_DELETE("deleteSnapshot"),
    FILE_DELETE("deleteFile");

    private final String value;

    DestructiveToolAction(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static DestructiveToolAction fromValue(String value) {
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported destructive tool action"));
    }
}
