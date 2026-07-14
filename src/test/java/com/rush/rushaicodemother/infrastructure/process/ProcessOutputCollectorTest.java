package com.rush.rushaicodemother.infrastructure.process;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessOutputCollectorTest {

    @Test
    void shouldKeepRawOutputButRedactLogsAndHeartbeatTail() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProcessOutputCollector.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProcessOutputCollector collector = new ProcessOutputCollector("test", "sanitizer", 1024);
        CompletableFuture<Void> completion;
        try {
            completion = collector.start(
                    new ByteArrayInputStream("token=secret-value\nnormal-output".getBytes(StandardCharsets.UTF_8)),
                    410_001,
                    "stdout"
            );
            ProcessOutputCollector.awaitCompletionPreservingInterrupt(completion, Duration.ofSeconds(1));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertTrue(collector.output().contains("secret-value"));
        assertFalse(collector.tailForLog().contains("secret-value"));
        String loggedContent = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(loggedContent.contains("secret-value"));
        assertTrue(loggedContent.contains("token=***"));
    }

    @Test
    void summaryPolicyShouldRetainOutputWithoutEmittingProcessLines() {
        Logger logger = (Logger) LoggerFactory.getLogger(ProcessOutputCollector.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ProcessOutputCollector collector = new ProcessOutputCollector(
                "git-command",
                "generation-commit",
                1024,
                StandardCharsets.UTF_8,
                ManagedProcessOutputLogPolicy.SUMMARY
        );
        try {
            CompletableFuture<Void> completion = collector.start(
                    new ByteArrayInputStream("create mode 100644 src/Main.java\n".getBytes(StandardCharsets.UTF_8)),
                    410_002,
                    "stdout"
            );
            ProcessOutputCollector.awaitCompletionPreservingInterrupt(completion, Duration.ofSeconds(1));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertTrue(collector.output().contains("src/Main.java"));
        String loggedContent = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(loggedContent.contains("src/Main.java"));
        assertFalse(loggedContent.contains("create mode"));
    }
}
