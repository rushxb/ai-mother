package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HostLocalGeneratedCodeProcessSandboxTest {

    @TempDir
    Path workspace;

    @Test
    void shouldPreserveExistingHostExecutionContract() {
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("pnpm.cmd", "run", "build"))
                .environment(Map.of("CI", "true"))
                .environmentVariablesToRemove(Set.of("NODE_OPTIONS"))
                .build();

        SandboxProcessPlan plan = new HostLocalGeneratedCodeProcessSandbox()
                .prepare(request, workspace.toAbsolutePath().normalize());

        assertEquals("host-local", plan.backend());
        assertEquals(request.command(), plan.hostCommand());
        assertEquals(request.environment(), plan.hostEnvironment());
        assertEquals(request.environmentVariablesToRemove(), plan.hostEnvironmentVariablesToRemove());
    }
}
