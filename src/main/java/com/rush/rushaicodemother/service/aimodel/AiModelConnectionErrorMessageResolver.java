package com.rush.rushaicodemother.service.aimodel;

import java.util.Locale;

/** 将连接异常转换为不会泄漏密钥、响应体或内部网络细节的稳定文案。 */
final class AiModelConnectionErrorMessageResolver {

    private static final int MAX_CAUSE_DEPTH = 8;
    private static final String GENERIC_MESSAGE = "模型连接测试失败，请检查配置";

    private AiModelConnectionErrorMessageResolver() {
    }

    /** 根据当前上下文解析 AI 模型连接错误消息。 */
    static String resolve(Throwable throwable) {
        String diagnosticText = collectDiagnosticText(throwable);
        if (diagnosticText.isBlank()) {
            return GENERIC_MESSAGE;
        }
        String normalized = diagnosticText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized, "unexpected character ('<'", "text/html", "<!doctype html", "<html")) {
            return "模型接口返回了 HTML 而不是 JSON，请检查接口地址或网关配置";
        }
        if (containsAny(normalized, "401", "403", "unauthorized", "forbidden", "invalid api key", "authentication")) {
            return "模型认证失败，请检查 API Key 是否正确，或当前账号是否具备该模型权限";
        }
        if (containsAny(normalized, "429", "too many requests", "rate limit", "insufficient quota", "insufficient balance")) {
            return "模型服务请求过于频繁或额度受限，请稍后重试并检查服务账户状态";
        }
        if (containsAny(normalized, "timeout", "timed out", "sockettimeoutexception")) {
            return "模型连接超时，请检查接口地址和网络连接后重试";
        }
        if (containsAny(normalized, "connection refused", "connection reset", "connectexception",
                "unknownhostexception", "502", "503", "504", "service unavailable")) {
            return "模型服务暂时不可用，请检查接口地址和网络连接后重试";
        }
        return GENERIC_MESSAGE;
    }

    /** 采集并汇总{@code Diagnostic}{@code Text}。 */
    private static String collectDiagnosticText(Throwable throwable) {
        StringBuilder text = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            text.append(' ').append(current.getClass().getName());
            if (current.getMessage() != null) {
                text.append(' ').append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return text.toString();
    }

    /** 返回{@code contains}{@code Any}。 */
    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
