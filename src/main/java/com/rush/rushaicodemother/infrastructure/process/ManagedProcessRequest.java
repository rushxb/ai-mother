package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;

import lombok.Builder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        String displayCommand,
        Map<String, String> environment,
        Set<String> environmentVariablesToRemove,
        Duration timeout,
        Duration idleTimeout,
        Duration heartbeatInterval,
        Duration outputDrainTimeout,
        int maxOutputLength,
        boolean redirectErrorStream,
        ManagedProcessOutputLogPolicy outputLogPolicy,
        Charset outputCharset,
        String logCategory,
        String logContext,
        BooleanSupplier cancellationRequested,
        ManagedProcessLifecycle lifecycle,
        SandboxNetworkPolicy networkPolicy
) {

    public ManagedProcessRequest {
        command = command == null ? null : List.copyOf(command);
        environment = environment == null ? Map.of() : Map.copyOf(environment);
        environmentVariablesToRemove = environmentVariablesToRemove == null
                ? Set.of()
                : Set.copyOf(environmentVariablesToRemove);
        outputLogPolicy = outputLogPolicy == null
                ? ManagedProcessOutputLogPolicy.STREAM
                : outputLogPolicy;
        outputCharset = outputCharset == null ? StandardCharsets.UTF_8 : outputCharset;
        cancellationRequested = cancellationRequested == null ? () -> false : cancellationRequested;
        lifecycle = lifecycle == null ? ManagedProcessLifecycle.NO_OP : lifecycle;
        networkPolicy = networkPolicy == null ? SandboxNetworkPolicy.NONE : networkPolicy;
    }
}
