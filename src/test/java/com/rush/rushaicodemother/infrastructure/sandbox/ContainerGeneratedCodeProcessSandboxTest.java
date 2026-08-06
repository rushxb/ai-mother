package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.infrastructure.process.GoProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContainerGeneratedCodeProcessSandboxTest {

    @TempDir
    Path workspace;

    @Test
    void shouldBuildLockedDownContainerPlanWithoutNetworkByDefault() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();

        SandboxProcessPlan plan = sandbox.prepare(
                request(SandboxNetworkPolicy.NONE),
                workspace.toAbsolutePath().normalize()
        );

        List<String> command = plan.hostCommand();
        assertEquals("container", plan.backend());
        assertEquals("docker", command.getFirst());
        assertOption(command, "--network", "none");
        assertOption(command, "--cap-drop", "ALL");
        assertOption(command, "--security-opt", "no-new-privileges");
        assertOption(command, "--pids-limit", "128");
        assertOption(command, "--memory", "1g");
        assertOption(command, "--memory-swap", "1g");
        assertOption(command, "--cpus", "1.5");
        assertOption(command, "--user", "1000:1000");
        assertTrue(command.contains("--read-only"));
        assertTrue(command.contains("--rm"));
        assertTrue(command.contains("--init"));
        assertEquals(1, command.stream().filter("--mount"::equals).count());
        assertTrue(optionValue(command, "--mount").startsWith(
                "type=bind,source=" + workspace.toAbsolutePath().normalize()));
        assertTrue(optionValue(command, "--mount").endsWith(",target=/workspace"));
        assertEquals(Map.of(), plan.hostEnvironment());
        assertEquals(Set.of("NODE_OPTIONS"), plan.hostEnvironmentVariablesToRemove());
    }

    @Test
    void shouldGrantConfiguredNetworkOnlyForDependencyInstallationAndMapWorkspacePaths() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox(true);
        Path lockfile = workspace.resolve("pnpm-lock.yaml").toAbsolutePath().normalize();

        SandboxProcessPlan plan = sandbox.prepare(
                ManagedProcessRequest.builder()
                        .workingDirectory(workspace)
                        .command(List.of("C:\\tools\\pnpm.cmd", "install", lockfile.toString()))
                        .environment(Map.of(
                                "LOCKFILE_PATH", lockfile.toString(),
                                "HOME", "C:\\Users\\host-user",
                                "INVALID-NAME", "ignored"
                        ))
                        .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                        .build(),
                workspace.toAbsolutePath().normalize()
        );

        List<String> command = plan.hostCommand();
        assertOption(command, "--network", "bridge");
        assertEquals(2, command.stream().filter("--mount"::equals).count());
        assertTrue(optionValues(command, "--mount").contains(
                "type=volume,source=ai-code-mother-pnpm-store-v9,target=/pnpm/store"));
        int imageIndex = command.indexOf("ai-code-mother/sandbox-node:1");
        assertTrue(imageIndex > 0);
        assertEquals(List.of(
                        "pnpm",
                        "install",
                        "/workspace/pnpm-lock.yaml",
                        "--store-dir",
                        "/pnpm/store",
                        "--package-import-method",
                        "copy",
                        "--verify-store-integrity"
                ),
                command.subList(imageIndex + 1, command.size()));
        assertTrue(command.contains("LOCKFILE_PATH=/workspace/pnpm-lock.yaml"));
        assertTrue(command.contains("HOME=/tmp/home"));
        assertFalse(command.contains("HOME=C:\\Users\\host-user"));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("INVALID-NAME=")));
    }

    @Test
    void shouldNotExposeSharedStoreOutsideManagedPnpmInstall() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox(true);
        Path normalizedWorkspace = workspace.toAbsolutePath().normalize();

        SandboxProcessPlan buildPlan = sandbox.prepare(
                request(SandboxNetworkPolicy.DEPENDENCY_EGRESS),
                normalizedWorkspace
        );
        SandboxProcessPlan offlineInstallPlan = sandbox.prepare(
                ManagedProcessRequest.builder()
                        .workingDirectory(workspace)
                        .command(List.of("pnpm", "install"))
                        .networkPolicy(SandboxNetworkPolicy.NONE)
                        .build(),
                normalizedWorkspace
        );

        for (SandboxProcessPlan plan : List.of(buildPlan, offlineInstallPlan)) {
            assertEquals(1, plan.hostCommand().stream().filter("--mount"::equals).count());
            assertFalse(plan.hostCommand().contains("/pnpm/store"));
        }
    }

    @Test
    void shouldRejectCallerOverridesOfReservedPnpmCacheOptions() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox(true);
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("pnpm", "install", "--store-dir=/workspace/untrusted-store"))
                .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> sandbox.prepare(request, workspace.toAbsolutePath().normalize())
        );
    }

    @Test
    void shouldGrantExecutableTmpfsOnlyToOfflineGoTests() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();
        Map<String, String> goEnvironment = new LinkedHashMap<>(GoProcessEnvironment.overrides());
        goEnvironment.put("GOCACHE", "C:\\host-cache");
        goEnvironment.put("GOTMPDIR", "C:\\host-tmp");
        ManagedProcessRequest goTestRequest = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("C:\\tools\\go.exe", "test", "-mod=readonly", "./..."))
                .environment(goEnvironment)
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();

        SandboxProcessPlan plan = sandbox.prepare(
                goTestRequest,
                workspace.toAbsolutePath().normalize()
        );

        List<String> command = plan.hostCommand();
        assertOption(command, "--network", "none");
        assertTrue(optionValues(command, "--tmpfs").contains(
                "/tmp:rw,nosuid,nodev,noexec,size=256m"));
        assertTrue(optionValues(command, "--tmpfs").contains(
                "/tmp/go-build:rw,nosuid,nodev,exec,size=512m"));
        assertTrue(command.contains("GOCACHE=/tmp/go-build/cache"));
        assertTrue(command.contains("GOTMPDIR=/tmp/go-build"));
        assertTrue(command.contains("GOPROXY=off"));
        assertTrue(command.contains("GOSUMDB=off"));
        assertFalse(command.contains("GOCACHE=C:\\host-cache"));
        assertFalse(command.contains("GOTMPDIR=C:\\host-tmp"));
        int imageIndex = command.indexOf("ai-code-mother/sandbox-node:1");
        assertEquals(
                List.of("go", "test", "-mod=readonly", "./..."),
                command.subList(imageIndex + 1, command.size())
        );
    }

    @Test
    void executableRecognitionMustNotDependOnHostPathSeparator() {
        // 容器授权判定曾依赖宿主 JVM 的 Path 分隔符语义：Linux 上 "\" 不是分隔符，
        // Windows 风格的工具链路径会被当作单段文件名，导致共享 store 与可执行 tmpfs
        // 授权静默失效。这里固定两种分隔符在任一宿主上都必须识别成同一可执行文件。
        for (String goExecutable : new String[]{
                "go", "go.exe", "/usr/local/go/bin/go", "C:\\tools\\go.exe",
                "C:/tools/go.exe", "\\\\build-host\\toolchain\\go.exe"
        }) {
            SandboxProcessPlan plan = sandbox().prepare(
                    ManagedProcessRequest.builder()
                            .workingDirectory(workspace)
                            .command(List.of(goExecutable, "test", "./..."))
                            .environment(GoProcessEnvironment.overrides())
                            .networkPolicy(SandboxNetworkPolicy.NONE)
                            .build(),
                    workspace.toAbsolutePath().normalize()
            );

            List<String> command = plan.hostCommand();
            assertTrue(optionValues(command, "--tmpfs").contains(
                            "/tmp/go-build:rw,nosuid,nodev,exec,size=512m"),
                    "离线 Go 编译必须获得可执行 tmpfs: " + goExecutable);
            int imageIndex = command.indexOf("ai-code-mother/sandbox-node:1");
            assertEquals(List.of("go", "test", "./..."),
                    command.subList(imageIndex + 1, command.size()),
                    "容器内可执行名必须归一化: " + goExecutable);
        }
    }

    @Test
    void sharedPnpmStoreRecognitionMustNotDependOnHostPathSeparator() {
        for (String pnpmExecutable : new String[]{
                "pnpm", "pnpm.cmd", "/usr/local/bin/pnpm", "C:\\tools\\pnpm.cmd",
                "C:/tools/pnpm.cmd"
        }) {
            SandboxProcessPlan plan = sandbox(true).prepare(
                    ManagedProcessRequest.builder()
                            .workingDirectory(workspace)
                            .command(List.of(pnpmExecutable, "install"))
                            .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                            .build(),
                    workspace.toAbsolutePath().normalize()
            );

            List<String> command = plan.hostCommand();
            assertTrue(optionValues(command, "--mount").contains(
                            "type=volume,source=ai-code-mother-pnpm-store-v9,target=/pnpm/store"),
                    "受管 pnpm install 必须挂载共享 store: " + pnpmExecutable);
        }
    }

    @Test
    void shouldGrantExecutableTmpfsToOfflineGoRunWithExposedPort() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("go", "run", "-mod=readonly", "./cmd/server"))
                .environment(Map.of("SERVER_ADDR", "127.0.0.1:5181"))
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();

        SandboxProcessPlan plan = sandbox.prepareDevServer(
                request,
                workspace.toAbsolutePath().normalize(),
                5181
        );

        List<String> command = plan.hostCommand();
        assertOption(command, "--network", "ai-code-sandbox-internal");
        assertTrue(optionValues(command, "--tmpfs").contains(
                "/tmp/go-build:rw,nosuid,nodev,exec,size=512m"));
        assertTrue(command.contains("GOCACHE=/tmp/go-build/cache"));
        assertTrue(command.contains("GOTMPDIR=/tmp/go-build"));
        assertTrue(command.contains("SERVER_ADDR=0.0.0.0:5181"));
        int imageIndex = command.indexOf("ai-code-mother/sandbox-node:1");
        assertEquals(
                List.of("go", "run", "-mod=readonly", "./cmd/server"),
                command.subList(imageIndex + 1, command.size())
        );
    }

    @Test
    void shouldRejectContainerBackendAddressThatDoesNotMatchExposedPort() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("go", "run", "./cmd/server"))
                .environment(Map.of("SERVER_ADDR", "0.0.0.0:9000"))
                .networkPolicy(SandboxNetworkPolicy.NONE)
                .build();

        assertThrows(
                IllegalArgumentException.class,
                () -> sandbox.prepareDevServer(
                        request,
                        workspace.toAbsolutePath().normalize(),
                        5181
                )
        );
    }

    @Test
    void shouldNotGrantExecutableTmpfsToNetworkEnabledGoTests() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("go", "test", "./..."))
                .networkPolicy(SandboxNetworkPolicy.DEPENDENCY_EGRESS)
                .build();

        SandboxProcessPlan plan = sandbox.prepare(
                request,
                workspace.toAbsolutePath().normalize()
        );

        List<String> command = plan.hostCommand();
        assertOption(command, "--network", "bridge");
        assertEquals(
                List.of("/tmp:rw,nosuid,nodev,noexec,size=256m"),
                optionValues(command, "--tmpfs")
        );
        assertFalse(command.stream().anyMatch(value -> value.startsWith("GOCACHE=")));
        assertFalse(command.stream().anyMatch(value -> value.startsWith("GOTMPDIR=")));
    }

    @Test
    void shouldKeepDevServerInternalAndPublishThroughControlledGateway() {
        ContainerGeneratedCodeProcessSandbox sandbox = sandbox();
        String previewLauncher = "const { createServer } = await import('vite');";
        ManagedProcessRequest request = ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of(
                        "node.exe",
                        "--input-type=module",
                        "--eval", previewLauncher,
                        "--",
                        "--host", "127.0.0.1",
                        "--port", "5180",
                        "--strictPort",
                        "--base", "/api/app/dev-server/proxy/21/"
                ))
                .build();

        SandboxProcessPlan plan = sandbox.prepareDevServer(
                request,
                workspace.toAbsolutePath().normalize(),
                5180
        );

        List<String> command = plan.hostCommand();
        assertOption(command, "--network", "ai-code-sandbox-internal");
        assertFalse(command.contains("--publish"));
        int imageIndex = command.indexOf("ai-code-mother/sandbox-node:1");
        assertEquals(List.of(
                    "node",
                    "--input-type=module",
                    "--eval", previewLauncher,
                    "--",
                    "--host", "0.0.0.0",
                    "--port", "5180",
                    "--strictPort",
                    "--base", "/api/app/dev-server/proxy/21/"
            ),
                command.subList(imageIndex + 1, command.size()));
        assertEquals(2, plan.activationCommands().size());
        List<String> gatewayCommand = plan.activationCommands().get(0);
        assertOption(gatewayCommand, "--network", "ai-code-sandbox-internal");
        assertOption(gatewayCommand, "--publish", "127.0.0.1:5180:5180");
        assertTrue(gatewayCommand.contains("/opt/ai-code-mother/preview-gateway.js"));
        assertEquals(List.of(
                        "docker",
                        "network",
                        "connect",
                        "ai-code-sandbox-preview-gateway",
                        plan.cleanupResourceIds().get(0)
                ),
                plan.activationCommands().get(1));
        assertEquals(2, plan.cleanupResourceIds().size());
        assertEquals(plan.cleanupResourceId(), plan.cleanupResourceIds().get(1));
    }

    private ContainerGeneratedCodeProcessSandbox sandbox() {
        return sandbox(false);
    }

    private ContainerGeneratedCodeProcessSandbox sandbox(boolean dependencyCacheEnabled) {
        GeneratedCodeSandboxProperties properties = new GeneratedCodeSandboxProperties();
        properties.getContainer().setDependencyCacheEnabled(dependencyCacheEnabled);
        return new ContainerGeneratedCodeProcessSandbox(properties);
    }

    private ManagedProcessRequest request(SandboxNetworkPolicy networkPolicy) {
        return ManagedProcessRequest.builder()
                .workingDirectory(workspace)
                .command(List.of("pnpm.cmd", "run", "build"))
                .environmentVariablesToRemove(Set.of("NODE_OPTIONS"))
                .networkPolicy(networkPolicy)
                .build();
    }

    private void assertOption(List<String> command, String option, String expectedValue) {
        assertEquals(expectedValue, optionValue(command, option));
    }

    private String optionValue(List<String> command, String option) {
        int index = command.indexOf(option);
        assertTrue(index >= 0, () -> "missing container option: " + option);
        assertTrue(index + 1 < command.size(), () -> "missing value for container option: " + option);
        return command.get(index + 1);
    }

    private List<String> optionValues(List<String> command, String option) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        for (int index = 0; index < command.size() - 1; index++) {
            if (option.equals(command.get(index))) {
                values.add(command.get(index + 1));
            }
        }
        return List.copyOf(values);
    }
}
