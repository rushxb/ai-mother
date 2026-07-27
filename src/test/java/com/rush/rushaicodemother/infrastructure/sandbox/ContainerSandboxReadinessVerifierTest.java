package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerSandboxReadinessVerifierTest {

    @Test
    void shouldInspectPreProvisionedPnpmStoreWhenCacheIsEnabled() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setDependencyCacheEnabled(true);
        List<List<String>> commands = new ArrayList<>();
        ContainerSandboxReadinessVerifier verifier = verifier(properties, commands, false);

        verifier.afterSingletonsInstantiated();

        assertTrue(commands.contains(List.of(
                "docker", "volume", "inspect", "ai-code-mother-pnpm-store-v9")));
    }

    @Test
    void shouldNotInspectPnpmStoreWhenCacheIsDisabled() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        List<List<String>> commands = new ArrayList<>();
        ContainerSandboxReadinessVerifier verifier = verifier(properties, commands, false);

        verifier.afterSingletonsInstantiated();

        assertFalse(commands.stream().anyMatch(command -> command.contains("volume")));
        assertTrue(commands.stream().anyMatch(command -> command.containsAll(List.of("node", "--version"))));
        assertTrue(commands.stream().anyMatch(command -> command.containsAll(List.of("pnpm", "--version"))));
        assertTrue(commands.stream().anyMatch(command -> command.containsAll(List.of("go", "version"))));
    }

    @Test
    void shouldFailStartupWhenConfiguredPnpmStoreIsMissing() {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setDependencyCacheEnabled(true);
        List<List<String>> commands = new ArrayList<>();
        ContainerSandboxReadinessVerifier verifier = verifier(properties, commands, true);

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                verifier::afterSingletonsInstantiated
        );

        assertTrue(exception.getMessage().contains("pnpm 缓存卷"));
    }

    private ContainerSandboxReadinessVerifier verifier(
            GeneratedCodeSandboxProperties properties,
            List<List<String>> commands,
            boolean failVolumeInspection
    ) {
        return ContainerSandboxReadinessVerifier.forTesting(
                properties,
                GeneratedCodeSandboxMetricsCollector.noOp(),
                processBuilder -> {
                    List<String> command = List.copyOf(processBuilder.command());
                    commands.add(command);
                    boolean volumeInspection = command.size() >= 3
                            && "volume".equals(command.get(1))
                            && "inspect".equals(command.get(2));
                    if (volumeInspection && failVolumeInspection) {
                        return new CompletedProcess(1, "");
                    }
                    String output = networkPolicyOutput(properties, command);
                    return new CompletedProcess(0, output);
                }
        );
    }

    private String networkPolicyOutput(
            GeneratedCodeSandboxProperties properties,
            List<String> command
    ) {
        if (!command.contains("--format={{.Internal}}")) {
            return "";
        }
        return command.getLast().equals(properties.getContainer().getDevServerNetwork())
                ? "true\n"
                : "false\n";
    }

    private static final class CompletedProcess extends Process {

        private final int exitCode;
        private final InputStream standardOutput;

        private CompletedProcess(int exitCode, String standardOutput) {
            this.exitCode = exitCode;
            this.standardOutput = new ByteArrayInputStream(
                    standardOutput.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return standardOutput;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return exitCode;
        }

        @Override
        public void destroy() {
            // Already completed.
        }
    }
}
