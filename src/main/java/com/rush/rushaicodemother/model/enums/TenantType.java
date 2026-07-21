package com.rush.rushaicodemother.model.enums;

import java.util.Arrays;

public enum TenantType {
    PERSONAL("personal"),
    ORGANIZATION("organization");

    private final String value;

    TenantType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static TenantType fromValue(String value) {
        return Arrays.stream(values())
                .filter(type -> type.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unsupported tenant type"));
    }
}
