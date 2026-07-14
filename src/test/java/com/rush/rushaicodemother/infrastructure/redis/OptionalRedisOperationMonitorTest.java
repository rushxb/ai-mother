package com.rush.rushaicodemother.infrastructure.redis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OptionalRedisOperationMonitorTest {

    @Test
    void shouldRecordLowCardinalityMetricsAndSuppressRepeatedFailureWarnings() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        OptionalRedisOperationMonitor monitor = new OptionalRedisOperationMonitor();
        Logger logger = (Logger) LoggerFactory.getLogger(OptionalRedisOperationMonitor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            RuntimeException failure = new RuntimeException(
                    "redis://default:super-secret@localhost:6379/0"
            );
            monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, failure);
            monitor.bindTo(meterRegistry);
            monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, failure);
            monitor.recordSuccess(OptionalRedisOperation.CHAT_MEMORY_GET);
            monitor.recordFailure(OptionalRedisOperation.CHAT_MEMORY_GET, failure);
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(3.0, meterRegistry.get("redis_optional_operations_total")
                .tag("operation", "chat_memory_get")
                .tag("result", "failure")
                .functionCounter()
                .count());
        assertEquals(1.0, meterRegistry.get("redis_optional_operations_total")
                .tag("operation", "chat_memory_get")
                .tag("result", "success")
                .functionCounter()
                .count());

        List<ILoggingEvent> warnings = appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .toList();
        assertEquals(2, warnings.size());
        String loggedContent = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(loggedContent.contains("RuntimeException"));
        assertTrue(loggedContent.contains("recovered"));
        assertFalse(loggedContent.contains("super-secret"));
        assertFalse(loggedContent.contains("redis://"));
        assertFalse(loggedContent.contains("localhost"));
    }
}
