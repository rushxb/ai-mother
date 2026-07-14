package com.rush.rushaicodemother.infrastructure.diagnostic;

import java.util.regex.Pattern;

/**
 * Sanitizes command and build diagnostics before they cross a user-, model-, trace-, or API-facing boundary.
 *
 * <p>Raw diagnostics remain available to trusted in-process callers. Public callers should use this class
 * instead of copying redaction expressions into individual tools or services.</p>
 */
public final class PublicDiagnosticSanitizer {

    public static final int DEFAULT_MAX_OUTPUT_LENGTH = 16_000;
    private static final String REDACTED = "[REDACTED]";
    private static final String TRUNCATION_MARKER = "\n... [diagnostic output truncated] ...\n";

    private static final Pattern PRIVATE_KEY_BLOCK = Pattern.compile(
            "-----BEGIN [^\\r\\n]*PRIVATE KEY-----[\\s\\S]*?-----END [^\\r\\n]*PRIVATE KEY-----",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern AUTHORIZATION_HEADER = Pattern.compile(
            "(?im)(\\bAuthorization\\s*:\\s*(?:Bearer|Basic)\\s+)[^\\s,;]+"
    );
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)((?:[\\\"']?)[A-Za-z0-9_.:/-]{0,80}(?:api[-_]?key|apikey|access[-_]?token|refresh[-_]?token|"
                    + "auth[-_]?token|registry[-_]?token|_authToken|token|client[-_]?secret|secret[-_]?key|"
                    + "secret|password|passwd|pwd)(?:[\\\"']?)\\s*[:=]\\s*)"
                    + "(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\s,;]+)"
    );
    private static final Pattern SENSITIVE_COMMAND_ARGUMENT = Pattern.compile(
            "(?i)(--?(?:api[-_]?key|access[-_]?token|auth[-_]?token|registry[-_]?token|token|"
                    + "client[-_]?secret|secret|password|passwd|pwd)(?:=|\\s+))"
                    + "(?:\\\"[^\\\"\\r\\n]*\\\"|'[^'\\r\\n]*'|[^\\s,;]+)"
    );
    private static final Pattern URL_USER_INFO = Pattern.compile(
            "(?i)(\\bhttps?://)[^\\s/@]+@"
    );
    private static final Pattern WINDOWS_ABSOLUTE_PATH = Pattern.compile(
            "(?i)(?<![A-Za-z0-9_])(?:[A-Z]:[\\\\/](?:[^\\s:;,\\\"'()<>|]+[\\\\/])*)"
                    + "([^\\\\/\\s:;,\\\"'()<>|]+)"
    );
    private static final Pattern UNIX_ABSOLUTE_PATH = Pattern.compile(
            "(?<![A-Za-z0-9_.-])/(?:home|Users|var|tmp|opt|srv|workspace|app)/"
                    + "(?:[^\\s:;,\\\"'()<>]+/)*([^/\\s:;,\\\"'()<>]+)"
    );

    private PublicDiagnosticSanitizer() {
    }

    public static String sanitizeForPublicOutput(String value) {
        return sanitizeForPublicOutput(value, DEFAULT_MAX_OUTPUT_LENGTH);
    }

    public static String sanitizeForPublicOutput(String value, int maxLength) {
        if (value == null || value.isEmpty() || maxLength <= 0) {
            return "";
        }
        String sanitized = PRIVATE_KEY_BLOCK.matcher(value).replaceAll("[REDACTED PRIVATE KEY]");
        sanitized = AUTHORIZATION_HEADER.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = SENSITIVE_COMMAND_ARGUMENT.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = SENSITIVE_ASSIGNMENT.matcher(sanitized).replaceAll("$1" + REDACTED);
        sanitized = URL_USER_INFO.matcher(sanitized).replaceAll("$1" + REDACTED + "@");
        sanitized = WINDOWS_ABSOLUTE_PATH.matcher(sanitized).replaceAll("[path]/$1");
        sanitized = UNIX_ABSOLUTE_PATH.matcher(sanitized).replaceAll("[path]/$1");
        return limitPreservingContext(sanitized, maxLength);
    }

    public static String sanitizeSingleLine(String value, int maxLength) {
        if (value == null || value.isBlank() || maxLength <= 0) {
            return "";
        }
        int expandedLimit = maxLength >= DEFAULT_MAX_OUTPUT_LENGTH / 4
                ? DEFAULT_MAX_OUTPUT_LENGTH
                : maxLength * 4;
        int sanitizationLimit = Math.max(maxLength, expandedLimit);
        String normalized = sanitizeForPublicOutput(value, sanitizationLimit)
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return limitPreservingContext(normalized, maxLength);
    }

    private static String limitPreservingContext(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        if (maxLength <= TRUNCATION_MARKER.length()) {
            return TRUNCATION_MARKER.substring(0, maxLength);
        }
        int availableLength = maxLength - TRUNCATION_MARKER.length();
        int headLength = Math.max(1, availableLength * 2 / 3);
        int tailLength = availableLength - headLength;
        return value.substring(0, headLength)
                + TRUNCATION_MARKER
                + value.substring(value.length() - tailLength);
    }
}
