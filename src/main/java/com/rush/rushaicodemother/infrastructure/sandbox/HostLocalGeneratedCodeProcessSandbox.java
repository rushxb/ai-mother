package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** Development-only backend that preserves the existing host process behavior. */
@Component
@ConditionalOnProperty(
        name = "app.generated-code-sandbox.mode",
        havingValue = "host-local",
        matchIfMissing = true
)
public class HostLocalGeneratedCodeProcessSandbox implements GeneratedCodeProcessSandbox {

    @Override
    public SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory) {
        return new SandboxProcessPlan(
                "host-local",
                normalizedWorkingDirectory,
                request.command(),
                request.environment(),
                request.environmentVariablesToRemove(),
                ""
        );
    }
}
