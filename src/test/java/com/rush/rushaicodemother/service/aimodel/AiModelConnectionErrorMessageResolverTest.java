package com.rush.rushaicodemother.service.aimodel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AiModelConnectionErrorMessageResolverTest {

    @Test
    void knownFailuresMustReturnStableMessagesWithoutSensitiveDetails() {
        assertEquals("模型接口返回了 HTML 而不是 JSON，请检查接口地址或网关配置",
                AiModelConnectionErrorMessageResolver.resolve(
                        new IllegalStateException("Unexpected character ('<' (code 60): <!doctype html>")));
        assertEquals("模型认证失败，请检查 API Key 是否正确，或当前账号是否具备该模型权限",
                AiModelConnectionErrorMessageResolver.resolve(
                        new IllegalStateException("HTTP 401 unauthorized: api-key=secret-value")));
        assertEquals("模型连接超时，请检查接口地址和网络连接后重试",
                AiModelConnectionErrorMessageResolver.resolve(new IllegalStateException("request timed out")));
    }

    @Test
    void unknownFailureMustNeverExposeRawExceptionMessage() {
        String message = AiModelConnectionErrorMessageResolver.resolve(
                new IllegalStateException("provider-api-key=secret-value"));

        assertEquals("模型连接测试失败，请检查配置", message);
        assertFalse(message.contains("secret-value"));
    }
}
