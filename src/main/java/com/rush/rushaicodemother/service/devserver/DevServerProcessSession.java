package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** A running Dev Server process and the sandbox resources owned by its lifecycle. */
final class DevServerProcessSession {

    private final Path projectDirectory;
    private final int port;
    private final Process process;
    private final CompletableFuture<Void> outputCompletion;
    private final SandboxProcessPlan sandboxPlan;
    private final AtomicBoolean sandboxCleanupStarted = new AtomicBoolean(false);

    DevServerProcessSession(
            Path projectDirectory,
            int port,
            Process process,
            CompletableFuture<Void> outputCompletion
    ) {
        this(projectDirectory, port, process, outputCompletion, null);
    }

    DevServerProcessSession(
            Path projectDirectory,
            int port,
            Process process,
            CompletableFuture<Void> outputCompletion,
            SandboxProcessPlan sandboxPlan
    ) {
        if (projectDirectory == null || process == null || outputCompletion == null) {
            throw new IllegalArgumentException("Dev Server process session parameters cannot be null");
        }
        this.projectDirectory = projectDirectory;
        this.port = port;
        this.process = process;
        this.outputCompletion = outputCompletion;
        this.sandboxPlan = sandboxPlan;
    }

    Path projectDirectory() {
        return projectDirectory;
    }

    int port() {
        return port;
    }

    Process process() {
        return process;
    }

    CompletableFuture<Void> outputCompletion() {
        return outputCompletion;
    }

    SandboxProcessPlan sandboxPlan() {
        return sandboxPlan;
    }

    boolean beginSandboxCleanup() {
        return sandboxCleanupStarted.compareAndSet(false, true);
    }
}
