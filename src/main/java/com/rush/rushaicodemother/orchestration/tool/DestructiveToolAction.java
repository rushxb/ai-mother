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

    /**
 * 根据输入数据创建当前对象。
 *
 * @param value 待处理值
 * @return {@code Destructive}工具动作
 */
    public static DestructiveToolAction fromValue(String value) {
        return Arrays.stream(values())
                .filter(action -> action.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported destructive tool action"));
    }
}
