package com.rush.rushaicodemother.common.query;

import java.util.Map;
import java.util.Objects;

/**
 * 将 API 排序字段解析为受信任的数据库列名。
 *
 * <p>客户端字段只能从显式白名单中选择；空值、空白值和未知字段统一回退到默认排序，
 * 避免空键访问不可变 Map，并阻止未经校验的字段进入 ORDER BY。</p>
 */
public final class SortFieldWhitelist {

    private final Map<String, String> allowedFields;
    private final String defaultColumn;

    /** 创建{@code Sort}{@code Field}{@code Whitelist}实例并完成必要的依赖和初始状态设置。 */
    private SortFieldWhitelist(Map<String, String> allowedFields, String defaultField) {
        Objects.requireNonNull(allowedFields, "allowedFields must not be null");
        if (allowedFields.isEmpty()) {
            throw new IllegalArgumentException("allowedFields must not be empty");
        }
        if (defaultField == null || defaultField.isBlank()) {
            throw new IllegalArgumentException("defaultField must not be blank");
        }

        this.allowedFields = Map.copyOf(allowedFields);
        this.defaultColumn = this.allowedFields.get(defaultField);
        if (this.defaultColumn == null || this.defaultColumn.isBlank()) {
            throw new IllegalArgumentException("defaultField must exist in allowedFields");
        }
    }

    public static SortFieldWhitelist of(String defaultField, Map<String, String> allowedFields) {
        return new SortFieldWhitelist(allowedFields, defaultField);
    }

    /**
     * 解析客户端排序字段；未提供或不在白名单内时返回默认数据库列。
     */
    public String resolve(String requestedField) {
        if (requestedField == null || requestedField.isBlank()) {
            return defaultColumn;
        }
        return allowedFields.getOrDefault(requestedField, defaultColumn);
    }
}
