package com.rush.rushaicodemother.infrastructure.diagnostic;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 创建日志安全的异常诊断，同时保留异常类型、原因结构和调用堆栈。
 *
 * <p>Throwable 消息经常包含提供者响应、凭证、请求负载或本地路径。
 * 因此，记录原始可抛出数据跨越了敏感数据边界。呼叫者应通过
 * 由 {@link #sanitize(Throwable)} 返回到记录器的清理后的 throwable。</p>
 */
public final class LogExceptionSanitizer {

    private static final int MAX_CAUSE_DEPTH = 12;
    private static final int MAX_MESSAGE_LENGTH = 1_000;

    private LogExceptionSanitizer() {
    }

    /**
 * 清理日志异常净化器中的敏感或不安全内容。
 *
 * @param throwable 待处理的异常
 * @return 日志异常净化器
 */
    public static Throwable sanitize(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        return sanitize(throwable, new IdentityHashMap<>(), 0);
    }

    /**
 * 清理消息中的敏感或不安全内容。
 *
 * @param throwable 待处理的异常
 * @return 处理后的消息文本
 */
    public static String sanitizeMessage(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        String safeMessage = sanitizeExceptionMessage(throwable.getMessage());
        String exceptionType = throwable.getClass().getSimpleName();
        return safeMessage.isBlank() ? exceptionType : exceptionType + ": " + safeMessage;
    }

    /**
 * 清理值中的敏感或不安全内容。
 *
 * @param value 待处理值
 * @param maxLength {@code maxLength} 对应的调用参数
 * @return 处理后的值文本
 */
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

    /** 清理日志异常净化器中的敏感或不安全内容。 */
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
