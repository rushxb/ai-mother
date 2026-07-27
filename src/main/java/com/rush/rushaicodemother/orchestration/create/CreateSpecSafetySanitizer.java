package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;

import java.util.regex.Pattern;

/**
 * 创建规格安全安全净化器。
 */
public class CreateSpecSafetySanitizer {

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern SCRIPT_BLOCK = Pattern.compile("(?is)<script[^>]*>.*?</script>");
    private static final Pattern SCRIPT_WORD = Pattern.compile("(?i)script|javascript:|onerror\\s*=|onload\\s*=");
    private static final Pattern SECRET_LIKE = Pattern.compile("(?i)(sk-[A-Za-z0-9_-]{8,}|api[_-]?key\\s*[:=]\\s*\\S+|token\\s*[:=]\\s*\\S+|password\\s*[:=]\\s*\\S+)");
    private static final Pattern PRIVATE_ENDPOINT = Pattern.compile("(?i)(localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|10\\.\\d+\\.\\d+\\.\\d+|192\\.168\\.\\d+\\.\\d+|172\\.(1[6-9]|2\\d|3[0-1])\\.\\d+\\.\\d+)");

    public String text(String value, int maxLength, String fallback) {
        String cleaned = StrUtil.blankToDefault(value, "");
        cleaned = SCRIPT_BLOCK.matcher(cleaned).replaceAll("");
        cleaned = HTML_TAG.matcher(cleaned).replaceAll("");
        cleaned = SCRIPT_WORD.matcher(cleaned).replaceAll("");
        cleaned = SECRET_LIKE.matcher(cleaned).replaceAll("[filtered]");
        cleaned = PRIVATE_ENDPOINT.matcher(cleaned).replaceAll("[local-endpoint]");
        cleaned = cleaned.replace("\u0000", "").strip();
        if (StrUtil.isBlank(cleaned)) {
            cleaned = StrUtil.blankToDefault(fallback, "");
        }
        if (maxLength > 0 && cleaned.length() > maxLength) {
            cleaned = cleaned.substring(0, maxLength);
        }
        return cleaned;
    }
}
