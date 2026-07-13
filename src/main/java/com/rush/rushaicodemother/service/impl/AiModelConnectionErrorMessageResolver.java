package com.rush.rushaicodemother.service.impl;

import java.util.Locale;

/**
 * 将模型连接异常转换为可公开展示的稳定文案。
 *
 * <p>异常原文可能包含 API Key、接口响应体或内部网络信息，只允许用于服务端日志，
 * 不得直接透传到管理端响应。</p>
 */
final class AiModelConnectionErrorMessageResolver {

    private static final int MAX_CAUSE_DEPTH = 8;

    private static final String HTML_RESPONSE_MESSAGE =
            "模型接口返回了 HTML 而不是 JSON，请检查接口地址或网关配置";
    private static final String AUTHENTICATION_MESSAGE =
            "模型认证失败，请检查 API Key 是否正确，或当前账号是否具备该模型权限";
    private static final String RATE_LIMIT_MESSAGE =
            "模型服务请求过于频繁或额度受限，请稍后重试并检查服务账户状态";
    private static final String TIMEOUT_MESSAGE =
            "模型连接超时，请检查接口地址和网络连接后重试";
    private static final String SERVICE_UNAVAILABLE_MESSAGE =
            "模型服务暂时不可用，请检查接口地址和网络连接后重试";
    private static final String GENERIC_MESSAGE =
            "模型连接测试失败，请检查配置";

    private AiModelConnectionErrorMessageResolver() {
    }

    static String resolve(Throwable throwable) {
        String diagnosticText = collectDiagnosticText(throwable);
        if (diagnosticText.isBlank()) {
            return GENERIC_MESSAGE;
        }

        String normalized = diagnosticText.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "unexpected character ('<'",
                "text/html",
                "<!doctype html",
                "<html")) {
            return HTML_RESPONSE_MESSAGE;
        }
        if (containsAny(normalized,
                "401",
                "403",
                "unauthorized",
                "forbidden",
                "invalid api key",
                "authentication")) {
            return AUTHENTICATION_MESSAGE;
        }
        if (containsAny(normalized,
                "429",
                "too many requests",
                "rate limit",
                "insufficient quota",
                "insufficient balance")) {
            return RATE_LIMIT_MESSAGE;
        }
        if (containsAny(normalized,
                "timeout",
                "timed out",
                "sockettimeoutexception")) {
            return TIMEOUT_MESSAGE;
        }
        if (containsAny(normalized,
                "connection refused",
                "connection reset",
                "connectexception",
                "unknownhostexception",
                "502",
                "503",
                "504",
                "service unavailable")) {
            return SERVICE_UNAVAILABLE_MESSAGE;
        }
        return GENERIC_MESSAGE;
    }

    private static String collectDiagnosticText(Throwable throwable) {
        StringBuilder diagnosticText = new StringBuilder();
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            diagnosticText.append(' ')
                    .append(current.getClass().getName());
            if (current.getMessage() != null) {
                diagnosticText.append(' ')
                        .append(current.getMessage());
            }
            current = current.getCause();
            depth++;
        }
        return diagnosticText.toString();
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
