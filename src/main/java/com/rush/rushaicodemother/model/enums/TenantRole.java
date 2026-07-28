package com.rush.rushaicodemother.model.enums;

import java.util.Arrays;

/**
 * 租户角色的可选类型。
 */
public enum TenantRole {
    VIEWER("viewer", 10),
    DEVELOPER("developer", 20),
    ADMIN("admin", 30),
    OWNER("owner", 40);

    private final String value;
    private final int authorityLevel;

    TenantRole(String value, int authorityLevel) {
        this.value = value;
        this.authorityLevel = authorityLevel;
    }

    public String getValue() {
        return value;
    }

    public boolean includes(TenantRole requiredRole) {
        return requiredRole != null && authorityLevel >= requiredRole.authorityLevel;
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param value 待处理值
 * @return 租户角色
 */
    public static TenantRole fromValue(String value) {
        return Arrays.stream(values())
                .filter(role -> role.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported tenant role"));
    }
}
