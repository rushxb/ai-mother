package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;

import java.nio.file.Path;
import java.util.List;

/** Strategy port that turns a generated-code command into an isolated host launch plan. */
public interface GeneratedCodeProcessSandbox {

    SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory);

    default SandboxProcessPlan prepareDevServer(
            ManagedProcessRequest request,
            Path normalizedWorkingDirectory,
            int hostPort
    ) {
        return prepare(request, normalizedWorkingDirectory);
    }

    default void activate(SandboxProcessPlan plan) {
    }

    default void cleanup(SandboxProcessPlan plan) {
    }

    /** Cleans durable resources after the process that created the original plan has disappeared. */
    default void cleanupResources(String backend, List<String> resourceIds) {
        if (resourceIds != null && !resourceIds.isEmpty()) {
            throw new UnsupportedOperationException(
                    "sandbox backend does not support durable resource recovery: " + backend);
        }
    }
}
