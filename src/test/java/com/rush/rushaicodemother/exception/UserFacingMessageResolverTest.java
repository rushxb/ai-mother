package com.rush.rushaicodemother.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFacingMessageResolverTest {

    private final UserFacingMessageResolver resolver = new UserFacingMessageResolver();

    @Test
    void chineseBusinessMessageShouldBePreserved() {
        assertEquals("应用不存在", resolver.resolve("  应用不存在  ", "操作失败"));
    }

    @Test
    void englishInternalMessageShouldUseChineseFallback() {
        assertEquals("请求参数错误", resolver.resolve(
                "Request payload is invalid", "请求参数错误"));
    }

    @Test
    void blankCandidateShouldUseChineseFallback() {
        assertEquals("服务暂时不可用", resolver.resolve("  ", "服务暂时不可用"));
    }

    @Test
    void invalidFallbackShouldUseStableChineseDefault() {
        String resolved = resolver.resolve("Provider unavailable", "Operation failed");

        assertEquals("操作失败，请稍后重试", resolved);
        assertTrue(resolver.containsChinese(resolved));
    }
}