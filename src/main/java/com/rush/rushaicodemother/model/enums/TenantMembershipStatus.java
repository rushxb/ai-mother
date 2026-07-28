package com.rush.rushaicodemother.model.enums;

import java.util.Arrays;

/**
 * 租户成员关系状态的可选类型。
 */
public enum TenantMembershipStatus {
    INVITED("invited"),
    ACTIVE("active"),
    SUSPENDED("suspended");

    private final String value;

    TenantMembershipStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param value 待处理值
 * @return 租户成员关系状态
 */
    public static TenantMembershipStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported tenant membership status"));
    }
}
