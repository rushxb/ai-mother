package com.rush.rushaicodemother.infrastructure.process;

import lombok.Builder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 受控外部进程执行请求。
 *
 * <p>命令必须以参数列表传入，执行器不会通过 shell 拼接或解释命令。</p>
 */
@Builder
public record ManagedProcessRequest(
        Path workingDirectory,
        List<String> command,
        Map<String, String> environment,
        Duration timeout,
        Duration idleTimeout,
        Duration heartbeatInterval,
        Duration outputDrainTimeout,
        int maxOutputLength,
        boolean redirectErrorStream,
        Charset outputCharset,
        String logCategory,
        String logContext,
        BooleanSupplier cancellationRequested
) {

    public ManagedProcessRequest {
        command = command == null ? null : List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        outputCharset = outputCharset == null ? StandardCharsets.UTF_8 : outputCharset;
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
    }
}
