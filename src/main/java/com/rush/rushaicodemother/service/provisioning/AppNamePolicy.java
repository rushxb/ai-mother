package com.rush.rushaicodemother.service.provisioning;

import cn.hutool.core.util.StrUtil;

/** 统一应用初始名称与 AI 润色结果的规范化规则。 */
final class AppNamePolicy {

    private static final int FALLBACK_APP_NAME_LENGTH = 12;
    private static final int MAX_APP_NAME_LENGTH = 16;

    private AppNamePolicy() {
    }

    static String initialName(String initPrompt) {
        String normalizedPrompt = StrUtil.trim(initPrompt)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ");
        if (StrUtil.isBlank(normalizedPrompt)) {
            return "未命名应用";
        }
        return truncateByCodePoints(normalizedPrompt, FALLBACK_APP_NAME_LENGTH);
    }

    /** 规范化{@code Generated}名称。 */
    static String normalizeGeneratedName(String appName) {
        if (StrUtil.isBlank(appName)) {
            return null;
        }
        String normalized = StrUtil.trim(appName)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("^(标题|应用名|应用名称)\\s*[:：]\\s*", "")
                .replaceAll("\\s+", " ")
                .replaceAll("^[\"'“”‘’《》【】\\s]+", "")
                .replaceAll("[\"'“”‘’《》【】\\s]+$", "");
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        return truncateByCodePoints(normalized, MAX_APP_NAME_LENGTH);
    }

    private static String truncateByCodePoints(String value, int maximumCodePoints) {
        int codePointCount = value.codePointCount(0, value.length());
        if (codePointCount <= maximumCodePoints) {
            return value;
        }
        int endIndex = value.offsetByCodePoints(0, maximumCodePoints);
        return value.substring(0, endIndex);
    }
}
