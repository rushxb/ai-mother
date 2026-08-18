package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/** CREATE 模板规则共享的大小写无关关键词匹配。 */
final class CreatePromptKeywordMatcher {

    private CreatePromptKeywordMatcher() {
    }

    static boolean containsAny(String value, String... keywords) {
        String normalized = StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (keyword != null && normalized.contains(keyword.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
