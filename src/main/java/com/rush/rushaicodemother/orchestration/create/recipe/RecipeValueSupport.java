package com.rush.rushaicodemother.orchestration.create.recipe;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.regex.Pattern;

/** Pure normalization and naming helpers shared by deterministic recipe modules. */
final class RecipeValueSupport {

    private static final Pattern IDENTIFIER_CLEANUP = Pattern.compile("[^A-Za-z0-9_]");

    private RecipeValueSupport() {
    }

    static String inferIndustry(String userMessage) {
        String normalized = StrUtil.blankToDefault(userMessage, "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "健身", "私教", "瑜伽", "运动")) return "健身运营";
        if (containsAny(normalized, "教育", "课程", "培训")) return "教育培训";
        if (containsAny(normalized, "商品", "订单", "库存", "电商")) return "电商零售";
        if (containsAny(normalized, "客户", "crm", "销售")) return "客户增长";
        return "业务";
    }

    static String inferBrand(String userMessage, String fallback) {
        if (containsAny(userMessage, "健身", "私教")) return "FitPilot";
        if (containsAny(userMessage, "教育", "课程")) return "知行云";
        if (containsAny(userMessage, "商品", "电商")) return "商策云";
        return fallback;
    }

    static String inferEntityName(String userMessage) {
        if (containsAny(userMessage, "课程")) return "Course";
        if (containsAny(userMessage, "商品", "产品")) return "Product";
        if (containsAny(userMessage, "订单")) return "Order";
        if (containsAny(userMessage, "客户")) return "Customer";
        if (containsAny(userMessage, "会员")) return "Member";
        return "Record";
    }

    static String inferEntityLabel(String userMessage) {
        if (containsAny(userMessage, "课程")) return "课程";
        if (containsAny(userMessage, "商品", "产品")) return "商品";
        if (containsAny(userMessage, "订单")) return "订单";
        if (containsAny(userMessage, "客户")) return "客户";
        if (containsAny(userMessage, "会员")) return "会员";
        return "业务记录";
    }

    static boolean containsAny(String value, String... keywords) {
        String normalized = StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    static String readableDomain(String domain) {
        return StrUtil.blankToDefault(domain, "").replace('_', ' ').strip();
    }

    static String firstNonBlank(String value, String fallback) {
        return StrUtil.isBlank(value) ? fallback : value.strip();
    }

    static String validHex(String value, String fallback) {
        String normalized = StrUtil.blankToDefault(value, "").strip();
        return normalized.matches("^#[0-9a-fA-F]{6}$") ? normalized : fallback;
    }

    static String lowerIdentifier(String value) {
        String cleaned = IDENTIFIER_CLEANUP.matcher(StrUtil.blankToDefault(value, "")).replaceAll("_");
        if (StrUtil.isBlank(cleaned)) {
            return "";
        }
        String pascal = pascal(cleaned);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    static String pascal(String value) {
        String cleaned = IDENTIFIER_CLEANUP.matcher(StrUtil.blankToDefault(value, "")).replaceAll("_");
        StringBuilder result = new StringBuilder();
        for (String part : cleaned.split("_+")) {
            if (StrUtil.isBlank(part)) {
                continue;
            }
            result.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        return result.isEmpty() ? "Record" : result.toString();
    }

    static String camel(String value) {
        String pascal = pascal(value);
        return pascal.substring(0, 1).toLowerCase(Locale.ROOT) + pascal.substring(1);
    }

    static String snake(String value) {
        return camel(value).replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    static String tableName(String packageName) {
        return snake(packageName) + "s";
    }

    static String fieldLabel(String field) {
        return switch (field) {
            case "name", "title" -> "名称";
            case "coach" -> "教练";
            case "price" -> "价格";
            case "capacity" -> "容量";
            case "owner" -> "负责人";
            case "status" -> "状态";
            case "remark" -> "备注";
            default -> pascal(field);
        };
    }

    static String escape(String value) {
        return StrUtil.blankToDefault(value, "").replace("\\", "\\\\").replace("'", "\\'");
    }
}
