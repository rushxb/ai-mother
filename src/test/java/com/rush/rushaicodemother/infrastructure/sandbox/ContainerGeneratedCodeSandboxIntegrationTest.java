package com.rush.rushaicodemother.infrastructure.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Tag("integration")
class ContainerGeneratedCodeSandboxIntegrationTest {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path workspace;

    private GeneratedCodeSandboxProperties properties;
    private ContainerGeneratedCodeProcessSandbox sandbox;

    @BeforeEach
    void setUp() {
        assumeTrue(
                Boolean.getBoolean("generatedCodeSandboxE2e"),
                "run scripts/verify-generated-code-sandbox to enable Docker Sandbox E2E tests"
        );
        properties = new GeneratedCodeSandboxProperties();
        properties.setMode(GeneratedCodeSandboxProperties.Mode.CONTAINER);
        properties.getContainer().setImage(System.getProperty(
                "generatedCodeSandboxImage",
                "ai-code-mother/sandbox-node:1"
        ));
        properties.getContainer().setDependencyNetwork(System.getProperty(
                "generatedCodeSandboxDependencyNetwork",
                "ai-code-sandbox-egress"
        ));
        properties.getContainer().setDevServerNetwork(System.getProperty(
                "generatedCodeSandboxDevServerNetwork",
                "ai-code-sandbox-internal"
        ));
        properties.getContainer().setPreviewGatewayNetwork(System.getProperty(
                "generatedCodeSandboxPreviewGatewayNetwork",
                "ai-code-sandbox-preview-gateway"
        ));
        properties.getContainer().setDependencyCacheEnabled(true);
        properties.getContainer().setPnpmStoreVolume(System.getProperty(
                "generatedCodeSandboxPnpmStoreVolume",
                "ai-code-mother-pnpm-store-v9"
        ));
        sandbox = new ContainerGeneratedCodeProcessSandbox(
                properties,
                new GeneratedCodeProcessEnvironmentPolicy()
        );
        assertDockerSuccess(List.of("image", "inspect", properties.getContainer().getImage()));
        assertDockerSuccess(List.of(
                "volume", "inspect", properties.getContainer().getPnpmStoreVolume()));
        assertDockerSuccess(List.of(
                "network", "inspect", properties.getContainer().getDependencyNetwork()));
        assertEquals("true", dockerOutput(List.of(
                "network",
                "inspect",
                "--format={{.Internal}}",
                properties.getContainer().getDependencyNetwork()
        )).trim());
        assertEquals("true", dockerOutput(List.of(
                "network",
                "inspect",
                "--format={{.Internal}}",
                properties.getContainer().getDevServerNetwork()
        )).trim());
        assertEquals("false", dockerOutput(List.of(
                "network",
                "inspect",
                "--format={{.Internal}}",
                properties.getContainer().getPreviewGatewayNetwork()
        )).trim());
    }

    @Test
    void shouldEnforceRuntimeIsolationAndRemoveCompletedContainer() throws Exception {
        Files.writeString(workspace.resolve("input.txt"), "sandbox-input", StandardCharsets.UTF_8);
        String probe = """
                const fs = require('fs');
                const status = fs.readFileSync('/proc/self/status', 'utf8');
                const routes = fs.readFileSync('/proc/net/route', 'utf8');
                let rootWritable = true;
                try { fs.writeFileSync('/sandbox-root-probe', 'x'); } catch (_) { rootWritable = false; }
                fs.writeFileSync('/workspace/output.txt', 'sandbox-output');
                const result = {
                  uid: process.getuid(),
                  rootWritable,
                  noNewPrivileges: /NoNewPrivs:\\s+1/.test(status),
                  capabilityMask: (status.match(/CapEff:\\s+([0-9a-f]+)/) || [])[1],
                  defaultRoute: /\\n[^\\n]+\\t00000000\\t/.test(routes),
                  hostSecretVisible: process.env.SANDBOX_E2E_HOST_SECRET !== undefined,
                  input: fs.readFileSync('/workspace/input.txt', 'utf8')
                };
                console.log(JSON.stringify(result));
                """;
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("node", "-e", probe))
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();
        SandboxProcessPlan plan = sandbox.prepare(request, workspace.toRealPath());

        ProcessResult result = runPlan(plan);
        sandbox.cleanup(plan);
        JsonNode output = OBJECT_MAPPER.readTree(result.stdout().trim());

        assertEquals(0, result.exitCode(), result.stderr());
        assertEquals(1000, output.path("uid").asInt());
        assertFalse(output.path("rootWritable").asBoolean());
        assertTrue(output.path("noNewPrivileges").asBoolean());
        assertEquals("0000000000000000", output.path("capabilityMask").asText());
        assertFalse(output.path("defaultRoute").asBoolean());
        assertFalse(output.path("hostSecretVisible").asBoolean());
        assertEquals("sandbox-input", output.path("input").asText());
        assertEquals("sandbox-output", Files.readString(workspace.resolve("output.txt")));
        assertContainerAbsent(plan.cleanupResourceId());
    }

    @Test
    void shouldApplyResourceLimitsAndExposeOnlyTheWorkspaceBindMount() throws Exception {
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("node", "-e", "setInterval(() => {}, 1000)"))
                .build();
        SandboxProcessPlan plan = sandbox.prepare(request, workspace.toRealPath());
        Process process = startPlan(plan);
        sandbox.activate(plan);

        try {
            JsonNode inspect = waitForInspect(plan.cleanupResourceId(), process);
            JsonNode hostConfig = inspect.path("HostConfig");
            JsonNode mounts = inspect.path("Mounts");

            assertTrue(hostConfig.path("ReadonlyRootfs").asBoolean());
            assertFalse(hostConfig.path("Privileged").asBoolean());
            assertEquals(128, hostConfig.path("PidsLimit").asInt());
            assertEquals(1_073_741_824L, hostConfig.path("Memory").asLong());
            assertEquals(1_500_000_000L, hostConfig.path("NanoCpus").asLong());
            assertEquals("none", hostConfig.path("NetworkMode").asText());
            assertEquals("1000:1000", inspect.path("Config").path("User").asText());
            assertTrue(hostConfig.path("CapDrop").toString().contains("ALL"));
            assertTrue(hostConfig.path("SecurityOpt").toString().contains("no-new-privileges"));
            assertTrue(hostConfig.path("Tmpfs").has("/tmp"));
            assertEquals(1, mounts.size());
            assertEquals("bind", mounts.get(0).path("Type").asText());
            assertEquals("/workspace", mounts.get(0).path("Destination").asText());
        } finally {
            sandbox.cleanup(plan);
            process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        assertContainerAbsent(plan.cleanupResourceId());
    }

    @Test
    void shouldExposePnpmStoreOnlyDuringManagedDependencyInstall() throws Exception {
        Files.writeString(
                workspace.resolve("package.json"),
                """
                        {
                          "name": "sandbox-cache-probe",
                          "version": "1.0.0",
                          "scripts": {
                            "preinstall": "node -e \"setInterval(() => {}, 1000)\""
                          }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("pnpm", "install"))
                .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                .build();
        SandboxProcessPlan plan = sandbox.prepare(request, workspace.toRealPath());
        Process process = startPlan(plan);

        try {
            JsonNode mounts = waitForInspect(plan.cleanupResourceId(), process).path("Mounts");
            assertEquals(2, mounts.size());
            assertTrue(hasMount(mounts, "bind", "/workspace", null));
            assertTrue(hasMount(
                    mounts,
                    "volume",
                    "/pnpm/store",
                    properties.getContainer().getPnpmStoreVolume()
            ));
        } finally {
            sandbox.cleanup(plan);
            process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        assertContainerAbsent(plan.cleanupResourceId());
    }

    @Test
    void shouldRunGoTestsOfflineWithDedicatedExecutableTmpfs() throws Exception {
        Files.writeString(workspace.resolve("go.mod"), "module sandbox-probe\n\ngo 1.23\n", StandardCharsets.UTF_8);
        Files.writeString(
                workspace.resolve("probe_test.go"),
                "package probe\n\nimport \"testing\"\n\nfunc TestProbe(t *testing.T) {}\n",
                StandardCharsets.UTF_8
        );
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("go", "test", "-mod=readonly", "-count=1", "-trimpath", "./..."))
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();
        SandboxProcessPlan plan = sandbox.prepare(request, workspace.toRealPath());

        ProcessResult result = runPlan(plan);
        sandbox.cleanup(plan);

        assertEquals(0, result.exitCode(), result.stderr() + result.stdout());
        assertContainerAbsent(plan.cleanupResourceId());
    }

    @Test
    void shouldRunGoBackendThroughControlledLoopbackGateway() throws Exception {
        int port = freePort();
        Files.writeString(
                workspace.resolve("go.mod"),
                "module sandbox-backend\n\ngo 1.23\n",
                StandardCharsets.UTF_8
        );
        Files.writeString(
                workspace.resolve("main.go"),
                """
                        package main

                        import (
                            "net/http"
                            "os"
                        )

                        func main() {
                            address := os.Getenv("SERVER_ADDR")
                            http.HandleFunc("/", func(writer http.ResponseWriter, request *http.Request) {
                                writer.Header().Set("Content-Type", "application/json")
                                _, _ = writer.Write([]byte(`{"code":0,"data":{"status":"ok"},"message":"ok"}`))
                            })
                            if err := http.ListenAndServe(address, nil); err != nil {
                                panic(err)
                            }
                        }
                        """,
                StandardCharsets.UTF_8
        );
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("go", "run", "-mod=readonly", "."))
                .environment(Map.of("SERVER_ADDR", "127.0.0.1:" + port))
                .networkPolicy(SandboxNetworkPolicy.RUNTIME_INTERNAL)
                .exposedPort(port)
                .build();
        SandboxProcessPlan plan = sandbox.prepareDevServer(request, workspace.toRealPath(), port);
        Process process = startPlan(plan);
        sandbox.activate(plan);

        try {
            String gatewayName = plan.cleanupResourceIds().get(0);
            String body = waitForHttp(
                    port,
                    process,
                    gatewayName,
                    Duration.ofSeconds(45)
            );
            JsonNode backendInspect = waitForInspect(plan.cleanupResourceId(), process);
            JsonNode gatewayInspect = waitForInspect(gatewayName, process);
            JsonNode binding = gatewayInspect.path("HostConfig")
                    .path("PortBindings")
                    .path(port + "/tcp")
                    .get(0);

            assertTrue(body.contains("\"status\":\"ok\""));
            assertTrue(backendInspect.path("Config").path("Env").toString()
                    .contains("SERVER_ADDR=0.0.0.0:" + port));
            assertTrue(backendInspect.path("HostConfig").path("Tmpfs").has("/tmp/go-build"));
            assertTrue(backendInspect.path("HostConfig").path("PortBindings").isEmpty());
            assertEquals("127.0.0.1", binding.path("HostIp").asText());
            assertEquals(String.valueOf(port), binding.path("HostPort").asText());
        } finally {
            sandbox.cleanup(plan);
            process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        for (String resourceId : plan.cleanupResourceIds()) {
            assertContainerAbsent(resourceId);
        }
    }

    @Test
    void shouldPublishDevServerOnlyOnHostLoopbackThroughInternalNetwork() throws Exception {
        int port = freePort();
        String server = """
                const http = require('http');
                const server = http.createServer((request, response) => response.end('sandbox-ready'));
                server.on('upgrade', (request, socket) => {
                  socket.write('HTTP/1.1 101 Switching Protocols\\r\\nConnection: Upgrade\\r\\nUpgrade: sandbox\\r\\n\\r\\n');
                });
                server.listen(%d, '0.0.0.0');
                """.formatted(port);
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("node", "-e", server))
                .networkPolicy(SandboxNetworkPolicy.RUNTIME_INTERNAL)
                .exposedPort(port)
                .build();
        SandboxProcessPlan plan = sandbox.prepareDevServer(request, workspace.toRealPath(), port);
        Process process = startPlan(plan);
        sandbox.activate(plan);

        try {
            String body = waitForHttp(port, process, plan.cleanupResourceIds().get(0));
            JsonNode devServerInspect = waitForInspect(plan.cleanupResourceId(), process);
            String gatewayName = plan.cleanupResourceIds().get(0);
            JsonNode gatewayInspect = waitForInspect(gatewayName, process);
            JsonNode gatewayHostConfig = gatewayInspect.path("HostConfig");
            JsonNode binding = gatewayHostConfig.path("PortBindings")
                    .path(port + "/tcp")
                    .get(0);

            assertEquals("sandbox-ready", body);
            assertWebSocketUpgrade(port);
            assertEquals(
                    properties.getContainer().getDevServerNetwork(),
                    devServerInspect.path("HostConfig").path("NetworkMode").asText()
            );
            assertTrue(devServerInspect.path("HostConfig").path("PortBindings").isEmpty());
            assertTrue(gatewayInspect.path("NetworkSettings").path("Networks")
                    .has(properties.getContainer().getDevServerNetwork()));
            assertTrue(gatewayInspect.path("NetworkSettings").path("Networks")
                    .has(properties.getContainer().getPreviewGatewayNetwork()));
            assertEquals("127.0.0.1", binding.path("HostIp").asText());
            assertEquals(String.valueOf(port), binding.path("HostPort").asText());
        } finally {
            sandbox.cleanup(plan);
            process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        for (String resourceId : plan.cleanupResourceIds()) {
            assertContainerAbsent(resourceId);
        }
    }

    private ProcessResult runPlan(SandboxProcessPlan plan) throws Exception {
        Process process = startPlan(plan);
        CompletableFuture<byte[]> stdout = readAsync(process.getInputStream());
        CompletableFuture<byte[]> stderr = readAsync(process.getErrorStream());
        if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            sandbox.cleanup(plan);
            throw new AssertionError("Sandbox process did not finish within " + PROCESS_TIMEOUT);
        }
        return new ProcessResult(
                process.exitValue(),
                new String(stdout.join(), StandardCharsets.UTF_8),
                new String(stderr.join(), StandardCharsets.UTF_8)
        );
    }

    private Process startPlan(SandboxProcessPlan plan) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(plan.hostCommand());
        builder.directory(plan.hostWorkingDirectory().toFile());
        builder.environment().putAll(plan.hostEnvironment());
        plan.hostEnvironmentVariablesToRemove().forEach(builder.environment()::remove);
        return builder.start();
    }

    private JsonNode waitForInspect(String containerName, Process containerProcess) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            ProcessResult result = docker(List.of("inspect", containerName));
            if (result.exitCode() == 0) {
                return OBJECT_MAPPER.readTree(result.stdout()).get(0);
            }
            if (!containerProcess.isAlive()) {
                throw new AssertionError(
                        "Sandbox container exited before inspect, exitCode="
                                + containerProcess.exitValue()
                                + ", stderr="
                                + new String(containerProcess.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                );
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Container did not become inspectable: " + containerName);
    }

    private String waitForHttp(
            int port,
            Process containerProcess,
            String gatewayContainerName
    ) throws Exception {
        return waitForHttp(
                port,
                containerProcess,
                gatewayContainerName,
                Duration.ofSeconds(10)
        );
    }

    private String waitForHttp(
            int port,
            Process containerProcess,
            String gatewayContainerName,
            Duration readinessTimeout
    ) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/"))
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();
        long deadline = System.nanoTime() + readinessTimeout.toNanos();
        String lastObservation = "no HTTP attempt completed";
        while (System.nanoTime() < deadline) {
            if (!containerProcess.isAlive()) {
                throw new AssertionError(
                        "Dev Server container exited before readiness, exitCode="
                                + containerProcess.exitValue()
                                + ", stderr="
                                + new String(containerProcess.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
                );
            }
            try {
                HttpResponse<String> response = client.send(
                        request,
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() == 200) {
                    return response.body();
                }
                lastObservation = "HTTP " + response.statusCode() + ": " + response.body();
            } catch (IOException exception) {
                lastObservation = exception.getClass().getSimpleName() + ": " + exception.getMessage();
            }
            Thread.sleep(100);
        }
        ProcessResult gatewayInspect = docker(List.of(
                "inspect",
                "--format=state={{.State.Status}} error={{.State.Error}} exit={{.State.ExitCode}}"
                        + " networks={{json .NetworkSettings.Networks}}"
                        + " ports={{json .HostConfig.PortBindings}}",
                gatewayContainerName
        ));
        ProcessResult gatewayLogs = docker(List.of("logs", gatewayContainerName));
        ProcessResult upstreamProbe = docker(List.of(
                "exec",
                gatewayContainerName,
                "node",
                "-e",
                "fetch('http://" + planDevServerName(gatewayContainerName) + ":" + port
                        + "').then(async r=>console.log(r.status,await r.text())).catch(e=>{console.error(e);process.exit(1)})"
        ));
        throw new AssertionError(
                "Published Dev Server port did not become ready: " + port
                        + ", lastObservation=" + lastObservation
                        + ", gatewayInspect=" + gatewayInspect.stdout().trim()
                        + ", gatewayInspectError=" + gatewayInspect.stderr().trim()
                        + ", gatewayLogs=" + gatewayLogs.stdout().trim()
                        + gatewayLogs.stderr().trim()
                        + ", upstreamProbe=" + upstreamProbe.stdout().trim()
                        + upstreamProbe.stderr().trim()
        );
    }

    private String planDevServerName(String gatewayContainerName) {
        return gatewayContainerName.endsWith("-gateway")
                ? gatewayContainerName.substring(0, gatewayContainerName.length() - "-gateway".length())
                : gatewayContainerName;
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private void assertWebSocketUpgrade(int port) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);
            socket.getOutputStream().write((
                    "GET /hmr HTTP/1.1\r\n"
                            + "Host: 127.0.0.1:" + port + "\r\n"
                            + "Connection: Upgrade\r\n"
                            + "Upgrade: websocket\r\n\r\n"
            ).getBytes(StandardCharsets.US_ASCII));
            byte[] response = new byte[128];
            int bytesRead = socket.getInputStream().read(response);
            assertTrue(bytesRead > 0, "WebSocket upgrade returned no response");
            assertTrue(
                    new String(response, 0, bytesRead, StandardCharsets.US_ASCII)
                            .startsWith("HTTP/1.1 101 Switching Protocols")
            );
        }
    }

    private void assertContainerAbsent(String containerName) {
        ProcessResult result = docker(List.of(
                "ps", "--all", "--quiet", "--filter", "name=^/" + containerName + "$"
        ));
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().isBlank(), "Sandbox container was not removed: " + containerName);
    }

    private boolean hasMount(JsonNode mounts, String type, String destination, String name) {
        for (JsonNode mount : mounts) {
            if (type.equals(mount.path("Type").asText())
                    && destination.equals(mount.path("Destination").asText())
                    && (name == null || name.equals(mount.path("Name").asText()))) {
                return true;
            }
        }
        return false;
    }

    private void assertDockerSuccess(List<String> arguments) {
        ProcessResult result = docker(arguments);
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private String dockerOutput(List<String> arguments) {
        ProcessResult result = docker(arguments);
        assertEquals(0, result.exitCode(), result.stderr());
        return result.stdout();
    }

    private ProcessResult docker(List<String> arguments) {
        try {
            List<String> command = new ArrayList<>();
            command.add(properties == null ? "docker" : properties.getContainer().getRuntime());
            command.addAll(arguments);
            Process process = new ProcessBuilder(command).start();
            CompletableFuture<byte[]> stdout = readAsync(process.getInputStream());
            CompletableFuture<byte[]> stderr = readAsync(process.getErrorStream());
            if (!process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ProcessResult(-1, "", "Docker command timed out: " + arguments);
            }
            return new ProcessResult(
                    process.exitValue(),
                    new String(stdout.join(), StandardCharsets.UTF_8),
                    new String(stderr.join(), StandardCharsets.UTF_8)
            );
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new ProcessResult(-1, "", exception.getMessage());
        }
    }

    private CompletableFuture<byte[]> readAsync(java.io.InputStream inputStream) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return inputStream.readAllBytes();
            } catch (IOException exception) {
                throw new IllegalStateException("failed to read process output", exception);
            }
        });
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
