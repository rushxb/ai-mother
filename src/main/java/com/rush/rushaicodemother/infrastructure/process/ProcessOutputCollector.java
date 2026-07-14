package com.rush.rushaicodemother.infrastructure.process;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 外部进程有界输出消费器，使用 UTF-8 增量解码并持续排空管道。
 */
@Slf4j
public final class ProcessOutputCollector {

    private static final int READ_BUFFER_SIZE = 1024;
    private static final int MAX_PENDING_LINE_LENGTH = 1000;
    private static final int MAX_HEARTBEAT_TAIL_LENGTH = 500;

    private final String logCategory;
    private final String logContext;
    private final int maxOutputLength;
    private final Charset outputCharset;
    private final ManagedProcessOutputLogPolicy outputLogPolicy;
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder pendingLine = new StringBuilder();
    private final AtomicLong lastOutputAt = new AtomicLong(System.nanoTime());

    public ProcessOutputCollector(String logCategory, String logContext, int maxOutputLength) {
        this(
                logCategory,
                logContext,
                maxOutputLength,
                StandardCharsets.UTF_8,
                ManagedProcessOutputLogPolicy.STREAM
        );
    }

    public ProcessOutputCollector(
            String logCategory,
            String logContext,
            int maxOutputLength,
            Charset outputCharset
    ) {
        this(
                logCategory,
                logContext,
                maxOutputLength,
                outputCharset,
                ManagedProcessOutputLogPolicy.STREAM
        );
    }

    public ProcessOutputCollector(
            String logCategory,
            String logContext,
            int maxOutputLength,
            Charset outputCharset,
            ManagedProcessOutputLogPolicy outputLogPolicy
    ) {
        if (maxOutputLength <= 0) {
            throw new IllegalArgumentException("最大输出长度必须大于 0");
        }
        this.logCategory = normalizeLogValue(logCategory, "external-process");
        this.logContext = normalizeLogValue(logContext, "unknown");
        this.maxOutputLength = maxOutputLength;
        this.outputCharset = outputCharset == null ? StandardCharsets.UTF_8 : outputCharset;
        this.outputLogPolicy = outputLogPolicy == null
                ? ManagedProcessOutputLogPolicy.STREAM
                : outputLogPolicy;
    }

    public CompletableFuture<Void> start(Process process) {
        return start(process.getInputStream(), process.pid(), "combined");
    }

    public CompletableFuture<Void> start(InputStream inputStream, long processId, String streamName) {
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("process-output-" + processId + "-" + normalizeThreadName(streamName))
                .start(() -> {
                    try {
                        drain(inputStream);
                        completion.complete(null);
                    } catch (Throwable exception) {
                        completion.completeExceptionally(exception);
                    }
                });
        return completion;
    }

    public long idleNanos(long nowNanos) {
        return Math.max(0, nowNanos - lastOutputAt.get());
    }

    public synchronized String output() {
        return output.toString()
                .replace("\0", "")
                .replace("\uFEFF", "");
    }

    public synchronized String tailForLog() {
        String normalized = SensitiveLogSanitizer.sanitize(output())
                .replace("\u001B", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        if (normalized.isEmpty()) {
            return "(暂无输出)";
        }
        return normalized.length() > MAX_HEARTBEAT_TAIL_LENGTH
                ? normalized.substring(normalized.length() - MAX_HEARTBEAT_TAIL_LENGTH)
                : normalized;
    }

    public static void awaitCompletionPreservingInterrupt(
            CompletableFuture<Void> completion,
            Duration timeout
    ) {
        boolean interrupted = Thread.interrupted();
        try {
            completion.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException exception) {
            // 主流程已负责进程状态判定，输出消费异常或收口超时不覆盖该结果。
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void drain(InputStream inputStream) throws IOException {
        char[] buffer = new char[READ_BUFFER_SIZE];
        try (Reader reader = new InputStreamReader(inputStream, outputCharset)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read);
                lastOutputAt.set(System.nanoTime());
                appendOutput(chunk);
                if (outputLogPolicy.isLineLoggingEnabled()) {
                    appendLogLines(chunk);
                }
            }
            if (outputLogPolicy.isLineLoggingEnabled()) {
                flushPendingLine();
            }
        } catch (IOException exception) {
            if (outputLogPolicy.isLineLoggingEnabled()) {
                flushPendingLine();
            }
            throw exception;
        }
    }

    private synchronized void appendOutput(String chunk) {
        output.append(chunk);
        if (output.length() > maxOutputLength) {
            output.delete(0, output.length() - maxOutputLength);
        }
    }

    private synchronized void appendLogLines(String chunk) {
        String normalized = chunk.replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        for (int index = 0; index < parts.length; index++) {
            if (index == parts.length - 1) {
                pendingLine.append(parts[index]);
                trimPendingLine();
                continue;
            }
            pendingLine.append(parts[index]);
            logLine(pendingLine.toString());
            pendingLine.setLength(0);
        }
    }

    private synchronized void flushPendingLine() {
        if (pendingLine.isEmpty()) {
            return;
        }
        logLine(pendingLine.toString());
        pendingLine.setLength(0);
    }

    private void trimPendingLine() {
        if (pendingLine.length() > MAX_PENDING_LINE_LENGTH) {
            pendingLine.delete(0, pendingLine.length() - MAX_PENDING_LINE_LENGTH);
        }
    }

    private void logLine(String line) {
        String normalized = SensitiveLogSanitizer.sanitize(line)
                .replace("\u001B", "")
                .trim();
        if (!normalized.isEmpty()) {
            log.info("[{}] {} | {}", logCategory, logContext, normalized);
        }
    }

    private static String normalizeLogValue(String value, String fallback) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim();
        return SensitiveLogSanitizer.sanitize(normalized);
    }

    private static String normalizeThreadName(String value) {
        return normalizeLogValue(value, "stream").replaceAll("[^A-Za-z0-9_-]", "-");
    }
}
