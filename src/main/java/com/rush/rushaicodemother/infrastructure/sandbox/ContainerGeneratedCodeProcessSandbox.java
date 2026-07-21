package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** OCI container backend with capability-scoped mounts and no ambient host credentials. */
@Component
@ConditionalOnProperty(name = "app.generated-code-sandbox.mode", havingValue = "container")
public class ContainerGeneratedCodeProcessSandbox implements GeneratedCodeProcessSandbox {

    private static final String CONTAINER_NAME_PREFIX = "ai-code-sandbox-";
    private static final String PREVIEW_GATEWAY_SCRIPT = "/opt/ai-code-mother/preview-gateway.js";
    private static final String PNPM_STORE_DIR_OPTION = "--store-dir";
    private static final String PNPM_PACKAGE_IMPORT_METHOD_OPTION = "--package-import-method";
    private static final Set<String> FIXED_ENVIRONMENT_KEYS = Set.of(
            "HOME", "XDG_CACHE_HOME", "NPM_CONFIG_CACHE", "COREPACK_HOME"
    );

    private final GeneratedCodeSandboxProperties.Container properties;

    public ContainerGeneratedCodeProcessSandbox(GeneratedCodeSandboxProperties properties) {
        this.properties = properties.getContainer();
    }

    @Override
    public SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory) {
        return prepareContainer(request, normalizedWorkingDirectory, null);
    }

    @Override
    public SandboxProcessPlan prepareDevServer(
            ManagedProcessRequest request,
            Path normalizedWorkingDirectory,
            int hostPort
    ) {
        if (hostPort < 1 || hostPort > 65535) {
            throw new IllegalArgumentException("container sandbox Dev Server port is invalid");
        }
        return prepareContainer(request, normalizedWorkingDirectory, hostPort);
    }

    private SandboxProcessPlan prepareContainer(
            ManagedProcessRequest request,
            Path normalizedWorkingDirectory,
            Integer devServerPort
    ) {
        String source = normalizedWorkingDirectory.toString();
        if (source.contains(",")) {
            throw new IllegalArgumentException("container sandbox workspace path cannot contain a comma");
        }
        String containerName = CONTAINER_NAME_PREFIX
                + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        String gatewayName = devServerPort == null ? null : containerName + "-gateway";
        boolean dependencyCacheEnabled = shouldMountDependencyCache(request, devServerPort != null);
        List<String> command = new ArrayList<>();
        command.add(properties.getRuntime());
        command.addAll(List.of(
                "run", "--rm", "--init", "--name", containerName,
                "--label", "ai-code-mother.generated-code-sandbox=true",
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", String.valueOf(properties.getPidsLimit()),
                "--memory", properties.getMemory(),
                "--memory-swap", properties.getMemory(),
                "--cpus", String.valueOf(properties.getCpus()),
                "--network", network(request.networkPolicy(), devServerPort != null),
                "--mount", "type=bind,source=" + source + ",target=" + properties.getWorkspaceMount(),
                "--workdir", properties.getWorkspaceMount(),
                "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=" + properties.getTmpfsSize()
        ));
        if (dependencyCacheEnabled) {
            command.addAll(List.of(
                    "--mount",
                    "type=volume,source=" + properties.getPnpmStoreVolume()
                            + ",target=" + properties.getPnpmStoreMount()
            ));
        }
        if (properties.isReadOnlyRoot()) {
            command.add("--read-only");
        }
        if (properties.getUser() != null && !properties.getUser().isBlank()) {
            command.addAll(List.of("--user", properties.getUser().trim()));
        }
        if (devServerPort != null) {
            command.addAll(List.of("--label", "ai-code-mother.sandbox-role=dev-server"));
        }
        containerEnvironment(request, normalizedWorkingDirectory).forEach((key, value) -> {
            command.add("--env");
            command.add(key + "=" + value);
        });
        command.add(properties.getImage());
        command.addAll(containerCommand(
                request.command(),
                normalizedWorkingDirectory,
                devServerPort != null,
                dependencyCacheEnabled
        ));
        List<List<String>> activationCommands = devServerPort == null
                ? List.of()
                : previewGatewayActivationCommands(
                        containerName,
                        gatewayName,
                        devServerPort
                );
        List<String> cleanupResourceIds = devServerPort == null
                ? List.of(containerName)
                : List.of(gatewayName, containerName);
        return new SandboxProcessPlan(
                "container",
                normalizedWorkingDirectory,
                List.copyOf(command),
                Map.of(),
                request.environmentVariablesToRemove(),
                containerName,
                activationCommands,
                cleanupResourceIds
        );
    }

    @Override
    public void activate(SandboxProcessPlan plan) {
        if (plan == null || plan.activationCommands().isEmpty()) {
            return;
        }
        for (List<String> command : plan.activationCommands()) {
            runActivationCommand(command);
        }
    }

    @Override
    public void cleanup(SandboxProcessPlan plan) {
        if (plan == null || plan.cleanupResourceIds().isEmpty()) {
            return;
        }
        cleanupResources(plan.backend(), plan.cleanupResourceIds());
    }

    @Override
    public void cleanupResources(String backend, List<String> resourceIds) {
        if (resourceIds == null || resourceIds.isEmpty()) {
            return;
        }
        if (!"container".equals(backend)) {
            throw new IllegalArgumentException("container sandbox cannot clean backend: " + backend);
        }
        RuntimeException cleanupFailure = null;
        for (String resourceId : resourceIds) {
            try {
                cleanupResource(resourceId);
            } catch (RuntimeException exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
        }
        if (cleanupFailure != null) {
            throw cleanupFailure;
        }
    }

    private void cleanupResource(String resourceId) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    properties.getRuntime(), "rm", "--force", resourceId)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(properties.getCleanupTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "timed out cleaning generated-code sandbox container: " + resourceId);
            }
            String errorOutput = new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            if (process.exitValue() != 0 && !alreadyRemoved(errorOutput)) {
                throw new IllegalStateException(
                        "generated-code sandbox container cleanup failed: " + resourceId);
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "failed to invoke generated-code sandbox cleanup for container: "
                            + resourceId,
                    exception
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException(
                    "generated-code sandbox cleanup was interrupted: " + resourceId,
                    exception
            );
        }
    }

    private List<List<String>> previewGatewayActivationCommands(
            String devServerContainerName,
            String gatewayName,
            int port
    ) {
        List<String> startGateway = new ArrayList<>();
        startGateway.add(properties.getRuntime());
        startGateway.addAll(List.of(
                "run", "--detach", "--init", "--name", gatewayName,
                "--label", "ai-code-mother.generated-code-sandbox=true",
                "--label", "ai-code-mother.sandbox-role=preview-gateway",
                "--label", "ai-code-mother.dev-server-container=" + devServerContainerName,
                "--cap-drop", "ALL",
                "--security-opt", "no-new-privileges",
                "--pids-limit", String.valueOf(properties.getPreviewGatewayPidsLimit()),
                "--memory", properties.getPreviewGatewayMemory(),
                "--memory-swap", properties.getPreviewGatewayMemory(),
                "--cpus", String.valueOf(properties.getPreviewGatewayCpus()),
                "--network", properties.getDevServerNetwork(),
                "--publish", "127.0.0.1:" + port + ":" + port,
                "--read-only",
                "--tmpfs", "/tmp:rw,nosuid,nodev,noexec,size=32m"
        ));
        if (properties.getUser() != null && !properties.getUser().isBlank()) {
            startGateway.addAll(List.of("--user", properties.getUser().trim()));
        }
        startGateway.addAll(List.of(
                "--env", "HOME=/tmp/home",
                properties.getImage(),
                "node", PREVIEW_GATEWAY_SCRIPT,
                devServerContainerName,
                String.valueOf(port),
                String.valueOf(port)
        ));
        return List.of(
                List.copyOf(startGateway),
                List.of(
                        properties.getRuntime(),
                        "network", "connect",
                        properties.getPreviewGatewayNetwork(),
                        gatewayName
                )
        );
    }

    private void runActivationCommand(List<String> command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(properties.getActivationTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("generated-code sandbox activation timed out");
            }
            String errorOutput = new String(
                    process.getErrorStream().readAllBytes(),
                    StandardCharsets.UTF_8
            );
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "generated-code sandbox activation failed: " + summarizeError(errorOutput));
            }
        } catch (IOException exception) {
            throw new IllegalStateException("failed to invoke generated-code sandbox activation", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("generated-code sandbox activation was interrupted", exception);
        }
    }

    private String summarizeError(String errorOutput) {
        if (errorOutput == null || errorOutput.isBlank()) {
            return "container runtime returned a non-zero exit code";
        }
        String normalized = errorOutput.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 300 ? normalized : normalized.substring(0, 300);
    }

    private boolean alreadyRemoved(String errorOutput) {
        return errorOutput != null
                && errorOutput.toLowerCase(Locale.ROOT).contains("no such container");
    }

    private String network(SandboxNetworkPolicy policy, boolean devServer) {
        if (devServer) {
            return properties.getDevServerNetwork();
        }
        return policy == SandboxNetworkPolicy.DEPENDENCY_EGRESS
                ? properties.getDependencyNetwork()
                : "none";
    }

    private Map<String, String> containerEnvironment(ManagedProcessRequest request, Path workingDirectory) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", "/tmp/home");
        environment.put("XDG_CACHE_HOME", "/tmp/cache");
        environment.put("NPM_CONFIG_CACHE", "/tmp/npm-cache");
        environment.put("COREPACK_HOME", "/tmp/corepack");
        request.environment().entrySet().stream()
                .filter(entry -> validEnvironmentName(entry.getKey()))
                .filter(entry -> !FIXED_ENVIRONMENT_KEYS.contains(entry.getKey().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> environment.put(
                        entry.getKey(), mapWorkspacePath(entry.getValue(), workingDirectory)));
        return Map.copyOf(environment);
    }

    private List<String> containerCommand(
            List<String> original,
            Path workingDirectory,
            boolean devServer,
            boolean dependencyCacheEnabled
    ) {
        List<String> command = new ArrayList<>(original.size());
        for (int index = 0; index < original.size(); index++) {
            String argument = original.get(index);
            if (index == 0) {
                command.add(normalizeExecutable(argument));
            } else if (devServer && "--host".equals(original.get(index - 1))) {
                command.add("0.0.0.0");
            } else {
                command.add(mapWorkspacePath(argument, workingDirectory));
            }
        }
        if (dependencyCacheEnabled) {
            rejectReservedPnpmCacheOptions(command);
            command.add(PNPM_STORE_DIR_OPTION);
            command.add(properties.getPnpmStoreMount());
            command.add(PNPM_PACKAGE_IMPORT_METHOD_OPTION);
            command.add("copy");
            command.add("--verify-store-integrity");
        }
        return List.copyOf(command);
    }

    private boolean shouldMountDependencyCache(ManagedProcessRequest request, boolean devServer) {
        if (!properties.isDependencyCacheEnabled()
                || devServer
                || request.networkPolicy() != SandboxNetworkPolicy.DEPENDENCY_EGRESS) {
            return false;
        }
        List<String> command = request.command();
        return command != null
                && command.size() >= 2
                && command.getFirst() != null
                && command.get(1) != null
                && "pnpm".equalsIgnoreCase(normalizeExecutable(command.getFirst()))
                && "install".equalsIgnoreCase(command.get(1));
    }

    private void rejectReservedPnpmCacheOptions(List<String> command) {
        for (int index = 2; index < command.size(); index++) {
            String argument = command.get(index);
            if (argument == null
                    || PNPM_STORE_DIR_OPTION.equals(argument)
                    || argument.startsWith(PNPM_STORE_DIR_OPTION + "=")
                    || PNPM_PACKAGE_IMPORT_METHOD_OPTION.equals(argument)
                    || argument.startsWith(PNPM_PACKAGE_IMPORT_METHOD_OPTION + "=")
                    || argument.startsWith("--verify-store-integrity")
                    || "--no-verify-store-integrity".equals(argument)) {
                throw new IllegalArgumentException(
                        "container sandbox reserves pnpm dependency cache options");
            }
        }
    }

    private String normalizeExecutable(String executable) {
        String fileName;
        try {
            fileName = Path.of(executable).getFileName().toString();
        } catch (RuntimeException ignored) {
            fileName = executable;
        }
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.equals("pnpm.cmd") || normalized.equals("pnpm.exe")) {
            return "pnpm";
        }
        if (normalized.equals("node.exe")) {
            return "node";
        }
        if (normalized.equals("git.exe")) {
            return "git";
        }
        return fileName;
    }

    private String mapWorkspacePath(String value, Path workingDirectory) {
        if (value == null || value.isBlank()) {
            return value;
        }
        try {
            Path candidate = Path.of(value).toAbsolutePath().normalize();
            if (candidate.startsWith(workingDirectory)) {
                Path relative = workingDirectory.relativize(candidate);
                String suffix = relative.toString().replace('\\', '/');
                return suffix.isBlank()
                        ? properties.getWorkspaceMount()
                        : properties.getWorkspaceMount() + "/" + suffix;
            }
        } catch (RuntimeException ignored) {
            // Non-path values remain unchanged.
        }
        return value;
    }

    private boolean validEnvironmentName(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
