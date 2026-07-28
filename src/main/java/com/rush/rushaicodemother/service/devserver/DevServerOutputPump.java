package com.rush.rushaicodemother.service.devserver;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 以 UTF-8 持续消费单个 Dev Server 的合并输出，并限制单行内存占用。
 */
@Slf4j
final class DevServerOutputPump {

    private static final int READ_BUFFER_SIZE = 1024;
    private static final String TRUNCATION_MARKER = " …[输出已截断]";

    private final int maxLineLength;

    DevServerOutputPump(int maxLineLength) {
        if (maxLineLength <= 0) {
            throw new IllegalArgumentException("最大输出行长度必须大于 0");
        }
        this.maxLineLength = maxLineLength;
    }

    /** 启动开发服务器输出{@code Pump}。 */
    CompletableFuture<Void> start(Process process, String logContext, Consumer<String> lineConsumer) {
        Objects.requireNonNull(process, "Dev Server 进程不能为空");
        Objects.requireNonNull(lineConsumer, "输出消费者不能为空");
        String safeLogContext = logContext == null ? "unknown" : logContext;
        CompletableFuture<Void> completion = new CompletableFuture<>();
        Thread.ofVirtual()
                .name("dev-server-output-" + process.pid())
                .start(() -> {
                    try {
                        drain(process.getInputStream(), safeLogContext, lineConsumer);
                        completion.complete(null);
                    } catch (Exception exception) {
                        completion.completeExceptionally(exception);
                    } catch (Error error) {
                        completion.completeExceptionally(error);
                        throw error;
                    }
                });
        return completion;
    }

    /** 等待完成{@code Preserving}{@code Interrupt}完成。 */
    static void awaitCompletionPreservingInterrupt(CompletableFuture<Void> completion, Duration timeout) {
        if (completion == null || timeout == null) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        try {
            completion.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            interrupted = true;
        } catch (ExecutionException | TimeoutException exception) {
            // 主进程生命周期已经收口；输出异常只影响诊断完整性，不改变停止结果。
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 处理{@code drain}。 */
    private void drain(InputStream inputStream,
                       String logContext,
                       Consumer<String> lineConsumer) throws IOException {
        StringBuilder pendingLine = new StringBuilder(Math.min(maxLineLength, READ_BUFFER_SIZE));
        boolean truncated = false;
        boolean previousWasCarriageReturn = false;
        char[] buffer = new char[READ_BUFFER_SIZE];

        try (Reader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8)) {
            int read;
            while ((read = reader.read(buffer)) != -1) {
                for (int index = 0; index < read; index++) {
                    char character = buffer[index];
                    if (character == '\r') {
                        emitLine(pendingLine, truncated, logContext, lineConsumer);
                        pendingLine.setLength(0);
                        truncated = false;
                        previousWasCarriageReturn = true;
                    } else if (character == '\n') {
                        if (!previousWasCarriageReturn) {
                            emitLine(pendingLine, truncated, logContext, lineConsumer);
                            pendingLine.setLength(0);
                            truncated = false;
                        }
                        previousWasCarriageReturn = false;
                    } else {
                        previousWasCarriageReturn = false;
                        if (pendingLine.length() < maxLineLength) {
                            pendingLine.append(character);
                        } else {
                            truncated = true;
                        }
                    }
                }
            }
            emitLine(pendingLine, truncated, logContext, lineConsumer);
        }
    }

    /** 发送{@code Line}事件。 */
    private void emitLine(StringBuilder pendingLine,
                          boolean truncated,
                          String logContext,
                          Consumer<String> lineConsumer) {
        if (pendingLine.isEmpty() && !truncated) {
            return;
        }
        String line = pendingLine.toString();
        if (truncated) {
            line = appendTruncationMarker(line);
        }
        String normalized = line.replace("\u001B", "").strip();
        if (normalized.isEmpty()) {
            return;
        }
        log.info("[dev-server] {} | {}", logContext, normalized);
        try {
            lineConsumer.accept(normalized);
        } catch (RuntimeException exception) {
            log.warn("分发 Dev Server 输出失败: context={}, error={}",
                    logContext, exception.getClass().getSimpleName());
        }
    }

    private String appendTruncationMarker(String line) {
        if (maxLineLength <= TRUNCATION_MARKER.length()) {
            return line.substring(0, maxLineLength);
        }
        int contentLength = maxLineLength - TRUNCATION_MARKER.length();
        return line.substring(0, Math.min(contentLength, line.length())) + TRUNCATION_MARKER;
    }
}