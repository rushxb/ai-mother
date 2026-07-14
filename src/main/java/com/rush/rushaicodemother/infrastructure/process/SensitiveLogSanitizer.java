package com.rush.rushaicodemother.infrastructure.process;

import java.util.regex.Pattern;

/** 对进入日志的命令和进程输出执行保守脱敏，不改变返回给业务层的原始输出。 */
final class SensitiveLogSanitizer {

    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passwd|token|secret|api[-_]?key|authorization|credential)"
                    + "(\\s*[:=]\\s*)([^\\s,;]+)"
    );
    private static final Pattern URI_USER_INFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^/@\\s]+@");

    private SensitiveLogSanitizer() {
    }

    static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return value == null ? "" : value;
        }
        String withoutUserInfo = URI_USER_INFO.matcher(value).replaceAll("$1***@");
        return KEY_VALUE_SECRET.matcher(withoutUserInfo).replaceAll("$1$2***");
    }
}
