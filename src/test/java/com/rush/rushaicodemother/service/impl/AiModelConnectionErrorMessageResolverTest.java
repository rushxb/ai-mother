package com.rush.rushaicodemother.service.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiModelConnectionErrorMessageResolverTest {

    @Test
    void htmlResponseMustReturnStablePublicMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("Unexpected character ('<' (code 60): <!doctype html>"));

        assertEquals("模型接口返回了 HTML 而不是 JSON，请检查接口地址或网关配置", message);
    }

    @Test
    void nestedAuthenticationFailureMustReturnStablePublicMessage() {
        RuntimeException exception = new RuntimeException(
                "request failed",
                new IllegalStateException("HTTP 401 unauthorized: api-key=secret-value"));

        String message = AiModelConnectionErrorMessageResolver.resolve(exception);

        assertEquals("模型认证失败，请检查 API Key 是否正确，或当前账号是否具备该模型权限", message);
        assertFalse(message.contains("secret-value"));
    }

    @Test
    void rateLimitMustReturnStablePublicMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("HTTP 429 too many requests"));

        assertEquals("模型服务请求过于频繁或额度受限，请稍后重试并检查服务账户状态", message);
    }

    @Test
    void timeoutMustReturnStablePublicMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("upstream request timed out"));

        assertEquals("模型连接超时，请检查接口地址和网络连接后重试", message);
    }

    @Test
    void connectionFailureMustReturnStablePublicMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("connection refused: internal-host:11434"));

        assertEquals("模型服务暂时不可用，请检查接口地址和网络连接后重试", message);
        assertFalse(message.contains("internal-host"));
    }

    @Test
    void unknownFailureMustNeverExposeRawExceptionMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("provider-api-key=secret-value"));

        assertEquals("模型连接测试失败，请检查配置", message);
        assertFalse(message.contains("secret-value"));
    }

    @Test
    void missingExceptionMustReturnGenericMessage() {
        assertEquals(
                "模型连接测试失败，请检查配置",
                AiModelConnectionErrorMessageResolver.resolve(null));
    }
}
