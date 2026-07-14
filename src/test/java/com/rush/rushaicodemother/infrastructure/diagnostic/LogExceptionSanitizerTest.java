package com.rush.rushaicodemother.infrastructure.diagnostic;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogExceptionSanitizerTest {

    @Test
    void shouldRedactThrowableMessagesWhilePreservingTypesCausesAndCallStacks() {
        IllegalArgumentException cause = new IllegalArgumentException("password=database-secret");
        cause.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.Repository", "load", "Repository.java", 42)
        });
        IllegalStateException failure = new IllegalStateException(
                "provider-api-key=secret-value at D:\\workspace\\project", cause);
        failure.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("example.Service", "execute", "Service.java", 17)
        });

        StringWriter output = new StringWriter();
        LogExceptionSanitizer.sanitize(failure).printStackTrace(new PrintWriter(output));
        String diagnostic = output.toString();

        assertFalse(diagnostic.contains("secret-value"));
        assertFalse(diagnostic.contains("database-secret"));
        assertFalse(diagnostic.contains("D:\\workspace"));
        assertFalse(diagnostic.contains("provider-api-key"));
        assertFalse(diagnostic.contains("password="));
        assertTrue(diagnostic.contains("sensitive diagnostic redacted"));
        assertTrue(diagnostic.contains("java.lang.IllegalStateException"));
        assertTrue(diagnostic.contains("java.lang.IllegalArgumentException"));
        assertTrue(diagnostic.contains("example.Service.execute(Service.java:17)"));
        assertTrue(diagnostic.contains("example.Repository.load(Repository.java:42)"));
    }

    @Test
    void shouldSanitizeStructuredLogValues() {
        String diagnostic = LogExceptionSanitizer.sanitizeValue(
                java.util.Map.of("reason", "registry-token=secret-value"), 200);

        assertFalse(diagnostic.contains("secret-value"));
        assertTrue(diagnostic.contains("registry-token=[REDACTED]"));
    }

    @Test
    void structuredLogSanitizationMustNotPropagateBrokenToStringImplementations() {
        Object brokenValue = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("password=must-not-escape");
            }
        };

        String diagnostic = LogExceptionSanitizer.sanitizeValue(brokenValue, 200);

        assertFalse(diagnostic.contains("must-not-escape"));
        assertTrue(diagnostic.contains("diagnostic unavailable"));
    }
}
