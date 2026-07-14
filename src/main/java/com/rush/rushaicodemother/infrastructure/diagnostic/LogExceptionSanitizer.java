package com.rush.rushaicodemother.infrastructure.diagnostic;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Creates log-safe exception diagnostics while retaining exception types, cause structure and call stacks.
 *
 * <p>Throwable messages frequently contain provider responses, credentials, request payloads or local paths.
 * Logging the original throwable therefore crosses a sensitive-data boundary. Callers should pass the
 * sanitized throwable returned by {@link #sanitize(Throwable)} to the logger instead.</p>
 */
public final class LogExceptionSanitizer {

    private static final int MAX_CAUSE_DEPTH = 12;
    private static final int MAX_MESSAGE_LENGTH = 1_000;

    private LogExceptionSanitizer() {
    }

    public static Throwable sanitize(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        return sanitize(throwable, new IdentityHashMap<>(), 0);
    }

    public static String sanitizeMessage(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        String safeMessage = sanitizeExceptionMessage(throwable.getMessage());
        String exceptionType = throwable.getClass().getSimpleName();
        return safeMessage.isBlank() ? exceptionType : exceptionType + ": " + safeMessage;
    }

    public static String sanitizeValue(Object value, int maxLength) {
        if (value == null || maxLength <= 0) {
            return "";
        }
        try {
            return PublicDiagnosticSanitizer.sanitizeSingleLine(String.valueOf(value), maxLength);
        } catch (RuntimeException exception) {
            return value.getClass().getSimpleName() + "[diagnostic unavailable]";
        }
    }

    private static Throwable sanitize(Throwable throwable,
                                      Map<Throwable, Boolean> visited,
                                      int depth) {
        String exceptionType = throwable.getClass().getName();
        if (depth >= MAX_CAUSE_DEPTH) {
            return diagnostic(exceptionType, "cause chain truncated", null, throwable.getStackTrace());
        }
        if (visited.put(throwable, Boolean.TRUE) != null) {
            return diagnostic(exceptionType, "cyclic cause omitted", null, throwable.getStackTrace());
        }

        Throwable sanitizedCause = throwable.getCause() == null
                ? null
                : sanitize(throwable.getCause(), visited, depth + 1);
        String safeMessage = sanitizeExceptionMessage(throwable.getMessage());
        return diagnostic(exceptionType, safeMessage, sanitizedCause, throwable.getStackTrace());
    }

    private static String sanitizeExceptionMessage(String message) {
        String safeMessage = PublicDiagnosticSanitizer.sanitizeSingleLine(message, MAX_MESSAGE_LENGTH);
        if (safeMessage.contains("[REDACTED")
                || safeMessage.contains("[path]/")
                || safeMessage.contains("diagnostic output truncated")) {
            return "sensitive diagnostic redacted";
        }
        return safeMessage;
    }

    private static Throwable diagnostic(String exceptionType,
                                        String safeMessage,
                                        Throwable sanitizedCause,
                                        StackTraceElement[] stackTrace) {
        SanitizedLogException diagnostic =
                new SanitizedLogException(exceptionType, safeMessage, sanitizedCause);
        diagnostic.setStackTrace(stackTrace == null ? new StackTraceElement[0] : stackTrace);
        return diagnostic;
    }

    private static final class SanitizedLogException extends RuntimeException {

        private SanitizedLogException(String exceptionType,
                                      String safeMessage,
                                      Throwable sanitizedCause) {
            super(formatMessage(exceptionType, safeMessage), sanitizedCause);
        }

        private static String formatMessage(String exceptionType, String safeMessage) {
            if (safeMessage == null || safeMessage.isBlank()) {
                return "Original exception type: " + exceptionType + "; message unavailable";
            }
            return "Original exception type: " + exceptionType + "; message: " + safeMessage;
        }
    }
}
