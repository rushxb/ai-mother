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
import java.util.function.BooleanSupplier;

/** OCI 容器后端具有功能范围的挂载，并且没有环境主机凭据。 */
@Component
@ConditionalOnProperty(name = "app.generated-code-sandbox.mode", havingValue = "container")
public class ContainerGeneratedCodeProcessSandbox implements GeneratedCodeProcessSandbox {

    private static final String CONTAINER_NAME_PREFIX = "ai-code-sandbox-";
    private static final String PREVIEW_GATEWAY_SCRIPT = "/opt/ai-code-mother/preview-gateway.js";
    private static final String GO_BUILD_TMP_DIR = "/tmp/go-build";
    private static final String PNPM_STORE_DIR_OPTION = "--store-dir";
    private static final String PNPM_PACKAGE_IMPORT_METHOD_OPTION = "--package-import-method";
    private static final Set<String> GO_COMPILATION_COMMANDS = Set.of(
            "build", "install", "run", "test"
    );
    private static final Set<String> FIXED_ENVIRONMENT_KEYS = Set.of(
            "HOME", "XDG_CACHE_HOME", "NPM_CONFIG_CACHE", "COREPACK_HOME",
            "GOCACHE", "GOMODCACHE", "GOTMPDIR"
    );

    private final GeneratedCodeSandboxProperties.Container properties;

    public ContainerGeneratedCodeProcessSandbox(GeneratedCodeSandboxProperties properties) {
        this.properties = properties.getContainer();
    }

    @Override
    public SandboxProcessPlan prepare(ManagedProcessRequest request, Path normalizedWorkingDirectory) {
        return prepareContainer(request, normalizedWorkingDirectory, null);
    }

    /**
 * 准备后续流程所需的开发服务器。
 *
 * @param request 请求参数
 * @param normalizedWorkingDirectory {@code normalizedWorkingDirectory} 对应的调用参数
 * @param hostPort 主机端口
 * @return 开发服务器
 */
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

    /** 准备后续流程所需的容器。 */
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
        boolean goCompilationCommand = isGoCompilationCommand(request.command())
                && request.networkPolicy() == SandboxNetworkPolicy.NONE;
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
        if (goCompilationCommand) {
            command.addAll(List.of(
                    "--tmpfs",
                    GO_BUILD_TMP_DIR + ":rw,nosuid,nodev,exec,size=" + properties.getGoBuildTmpfsSize()
            ));
        }
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
        containerEnvironment(
                request,
                normalizedWorkingDirectory,
                goCompilationCommand,
                devServerPort
        )
                .forEach((key, value) -> {
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

    /**
 * 处理{@code activate}。
 *
 * @param plan 计划
 */
    @Override
    public void activate(SandboxProcessPlan plan) {
        int commandCount = plan == null ? 0 : plan.activationCommands().size();
        if (commandCount == 0) {
            return;
        }
        Duration timeout;
        try {
            timeout = properties.getActivationTimeout().multipliedBy(commandCount);
        } catch (ArithmeticException exception) {
            timeout = Duration.ofNanos(Long.MAX_VALUE);
        }
        activate(plan, timeout, () -> false);
    }

    @Override
    public void activate(
            SandboxProcessPlan plan,
            Duration remainingTimeout,
            BooleanSupplier cancellationRequested
    ) {
        if (plan == null || plan.activationCommands().isEmpty()) {
            return;
        }
        if (remainingTimeout == null || remainingTimeout.isZero() || remainingTimeout.isNegative()) {
            throw new IllegalArgumentException("容器沙箱激活剩余时限必须大于 0");
        }
        BooleanSupplier effectiveCancellation = cancellationRequested == null
                ? () -> false
                : cancellationRequested;
        long activationStartedAt = System.nanoTime();
        long totalTimeoutNanos = remainingTimeout.toNanos();
        for (List<String> command : plan.activationCommands()) {
            runActivationCommand(
                    command,
                    activationStartedAt,
                    totalTimeoutNanos,
                    effectiveCancellation
            );
        }
    }

    /**
 * 清理容器{@code Generated}代码进程{@code Sandbox}及其关联资源。
 *
 * @param plan 计划
 */
    @Override
    public void cleanup(SandboxProcessPlan plan) {
        if (plan == null || plan.cleanupResourceIds().isEmpty()) {
            return;
        }
        cleanupResources(plan.backend(), plan.cleanupResourceIds());
    }

    /**
 * 清理{@code Resources}及其关联资源。
 *
 * @param backend 后端
 * @param resourceIds 待处理的 {@code resourceIds} 集合
 */
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

    /** 清理资源及其关联资源。 */
    private void cleanupResource(String resourceId) {
        Process process = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
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

    /** 返回预览{@code Gateway}{@code Activation}{@code Commands}。 */
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

    /** 运行{@code Activation}命令处理流程。 */
    private void runActivationCommand(
            List<String> command,
            long activationStartedAt,
            long totalTimeoutNanos,
            BooleanSupplier cancellationRequested
    ) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            long commandStartedAt = System.nanoTime();
            long commandTimeoutNanos = properties.getActivationTimeout().toNanos();
            while (true) {
                if (cancellationRequested.getAsBoolean()) {
                    process.destroyForcibly();
                    throw new IllegalStateException("容器沙箱激活已取消");
                }
                long now = System.nanoTime();
                long totalRemainingNanos = totalTimeoutNanos - (now - activationStartedAt);
                long commandRemainingNanos = commandTimeoutNanos - (now - commandStartedAt);
                long remainingNanos = Math.min(totalRemainingNanos, commandRemainingNanos);
                if (remainingNanos <= 0) {
                    process.destroyForcibly();
                    throw new IllegalStateException("容器沙箱激活超时");
                }
                long waitMillis = Math.max(
                        1L,
                        Math.min(100L, TimeUnit.NANOSECONDS.toMillis(remainingNanos))
                );
                if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                    break;
                }
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

    /** 返回容器{@code Environment}。 */
    private Map<String, String> containerEnvironment(
            ManagedProcessRequest request,
            Path workingDirectory,
            boolean goCompilationCommand,
            Integer devServerPort
    ) {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HOME", "/tmp/home");
        environment.put("XDG_CACHE_HOME", "/tmp/cache");
        environment.put("NPM_CONFIG_CACHE", "/tmp/npm-cache");
        environment.put("COREPACK_HOME", "/tmp/corepack");
        if (goCompilationCommand) {
            environment.put("GOCACHE", GO_BUILD_TMP_DIR + "/cache");
            environment.put("GOTMPDIR", GO_BUILD_TMP_DIR);
        }
        request.environment().entrySet().stream()
                .filter(entry -> validEnvironmentName(entry.getKey()))
                .filter(entry -> !FIXED_ENVIRONMENT_KEYS.contains(entry.getKey().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> environment.put(
                        entry.getKey(),
                        mapContainerEnvironmentValue(
                                entry.getKey(),
                                entry.getValue(),
                                workingDirectory,
                                devServerPort
                        )));
        return Map.copyOf(environment);
    }

    /** 将输入映射为容器{@code Environment}值。 */
    private String mapContainerEnvironmentValue(
            String name,
            String value,
            Path workingDirectory,
            Integer devServerPort
    ) {
        if (devServerPort != null && "SERVER_ADDR".equalsIgnoreCase(name)) {
            String expected = "127.0.0.1:" + devServerPort;
            if (!expected.equals(value)) {
                throw new IllegalArgumentException("容器后端监听地址必须与受控回环端口一致");
            }
            return "0.0.0.0:" + devServerPort;
        }
        return mapWorkspacePath(value, workingDirectory);
    }

    /** 返回容器命令。 */
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

    /** 判断是否应执行挂载依赖缓存。 */
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

    private boolean isGoCompilationCommand(List<String> command) {
        return command != null
                && command.size() >= 2
                && command.getFirst() != null
                && command.get(1) != null
                && "go".equalsIgnoreCase(normalizeExecutable(command.getFirst()))
                && GO_COMPILATION_COMMANDS.contains(command.get(1).toLowerCase(Locale.ROOT));
    }

    /** 拒绝{@code Reserved}{@code Pnpm}缓存{@code Options}并记录原因。 */
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

    /**
     * 规范化{@code Executable}。
     *
     * <p>容器内的网络与 tmpfs 授权依赖这里识别出的可执行文件名，因此不能依赖宿主 JVM 的
     * {@link Path} 分隔符语义：Linux 上 {@code \} 不是分隔符，会把
     * {@code C:\tools\pnpm.cmd} 当成单段文件名，导致 pnpm 共享 store 与离线 Go 可执行
     * tmpfs 的分发判定失效（fail-closed，构建反而会失败）。此处显式按 {@code /} 与
     * {@code \} 双分隔符取末段，使判定与宿主操作系统无关。</p>
     */
    private String normalizeExecutable(String executable) {
        String fileName = executableFileName(executable);
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
        if (normalized.equals("go.exe")) {
            return "go";
        }
        return fileName;
    }

    /** 按 POSIX 与 Windows 两种分隔符取可执行文件名末段，不依赖宿主操作系统。 */
    private String executableFileName(String executable) {
        if (executable == null || executable.isBlank()) {
            return executable == null ? "" : executable;
        }
        int lastSeparator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String fileName = lastSeparator < 0
                ? executable
                : executable.substring(lastSeparator + 1);
        // 末尾是分隔符时回退到原值，避免把目录路径误判为空可执行名。
        return fileName.isBlank() ? executable : fileName;
    }

    /** 将输入映射为工作区路径。 */
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
            // 非路径值保持不变。
        }
        return value;
    }

    private boolean validEnvironmentName(String value) {
        return value != null && value.matches("[A-Za-z_][A-Za-z0-9_]*");
    }
}
