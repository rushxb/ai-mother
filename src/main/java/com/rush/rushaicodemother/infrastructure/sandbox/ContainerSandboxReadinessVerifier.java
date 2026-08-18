package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 配置容器模式但其运行时或不可变映像不存在时启动失败。 */
@Component
@ConditionalOnProperty(name = "app.generated-code-sandbox.mode", havingValue = "container")
public class ContainerSandboxReadinessVerifier implements SmartInitializingSingleton {

    private final GeneratedCodeSandboxProperties.Container properties;
    private final GeneratedCodeSandboxMetricsCollector sandboxMetrics;
    private final ProcessStarter processStarter;

    @Autowired
    public ContainerSandboxReadinessVerifier(
            GeneratedCodeSandboxProperties properties,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics
    ) {
        this(properties, sandboxMetrics, ProcessBuilder::start);
    }

    private ContainerSandboxReadinessVerifier(
            GeneratedCodeSandboxProperties properties,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics,
            ProcessStarter processStarter
    ) {
        this.properties = properties.getContainer();
        this.sandboxMetrics = sandboxMetrics;
        this.processStarter = processStarter;
    }

    static ContainerSandboxReadinessVerifier forTesting(
            GeneratedCodeSandboxProperties properties,
            GeneratedCodeSandboxMetricsCollector sandboxMetrics,
            ProcessStarter processStarter
    ) {
        return new ContainerSandboxReadinessVerifier(properties, sandboxMetrics, processStarter);
    }

    /** 在 Spring 单例 Bean 初始化完成后执行启动校验。 */
    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isVerifyOnStartup()) {
            return;
        }
        verifyAvailable(
                "image",
                "生成代码容器运行时或指定镜像不可用",
                List.of("image", "inspect", properties.getImage())
        );
        verifyImageTool("node_toolchain", "node", "--version");
        verifyImageTool("pnpm_toolchain", "pnpm", "--version");
        verifyImageTool("go_toolchain", "go", "version");
        if (properties.isDependencyCacheEnabled()) {
            verifyAvailable(
                    "pnpm_store_volume",
                    "生成代码 pnpm 缓存卷不可用",
                    List.of("volume", "inspect", properties.getPnpmStoreVolume())
            );
        }
        verifyAvailable(
                "dependency_network",
                "生成代码依赖出口网络不可用",
                List.of("network", "inspect", properties.getDependencyNetwork())
        );
        verifyAvailable(
                "dev_server_network",
                "生成代码 Dev Server 内部网络不可用",
                List.of("network", "inspect", properties.getDevServerNetwork())
        );
        verifyAvailable(
                "preview_gateway_network",
                "生成代码预览网关网络不可用",
                List.of("network", "inspect", properties.getPreviewGatewayNetwork())
        );
        verifyNetworkInternalPolicy(
                properties.getDependencyNetwork(),
                true,
                "dependency_network_policy"
        );
        verifyNetworkInternalPolicy(
                properties.getDevServerNetwork(),
                true,
                "dev_server_network_policy"
        );
        verifyNetworkInternalPolicy(
                properties.getPreviewGatewayNetwork(),
                false,
                "preview_gateway_network_policy"
        );
    }

    /** 验证可用是否符合预期。 */
    private void verifyAvailable(
            String metricResource,
            String unavailableMessage,
            List<String> arguments
    ) {
        Process process = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            List<String> command = new ArrayList<>(arguments.size() + 1);
            command.add(properties.getRuntime());
            command.addAll(arguments);
            ProcessBuilder processBuilder = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);
            process = processStarter.start(processBuilder);
            boolean completed = process.waitFor(
                    properties.getStartupVerificationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException("generated-code container sandbox readiness check timed out");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(unavailableMessage);
            }
            sandboxMetrics.recordReadiness(metricResource, "ready");
        } catch (IOException exception) {
            sandboxMetrics.recordReadiness(metricResource, "failure");
            throw new IllegalStateException(
                    "generated-code container sandbox runtime could not be started", exception);
        } catch (InterruptedException exception) {
            sandboxMetrics.recordReadiness(metricResource, "interrupted");
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException("generated-code container sandbox readiness check was interrupted", exception);
        } catch (RuntimeException exception) {
            sandboxMetrics.recordReadiness(metricResource, "failure");
            throw exception;
        }
    }

    /** 验证{@code Image}工具是否符合预期。 */
    private void verifyImageTool(String metricResource, String executable, String argument) {
        List<String> command = new ArrayList<>();
        command.add("run");
        command.add("--rm");
        command.add("--network");
        command.add("none");
        command.add("--read-only");
        if (properties.getUser() != null && !properties.getUser().isBlank()) {
            command.add("--user");
            command.add(properties.getUser().trim());
        }
        command.add(properties.getImage());
        command.add(executable);
        command.add(argument);
        verifyAvailable(
                metricResource,
                "生成代码容器镜像缺少必需工具链: " + executable,
                command
        );
    }

    /** 验证{@code Network}内部策略是否符合预期。 */
    private void verifyNetworkInternalPolicy(
            String network,
            boolean expectedInternal,
            String metricResource
    ) {
        Process process = null;
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    properties.getRuntime(),
                    "network",
                    "inspect",
                    "--format={{.Internal}}",
                    network
            ).redirectError(ProcessBuilder.Redirect.DISCARD);
            process = processStarter.start(processBuilder);
            boolean completed = process.waitFor(
                    properties.getStartupVerificationTimeout().toMillis(),
                    TimeUnit.MILLISECONDS
            );
            if (!completed) {
                process.destroyForcibly();
                throw new IllegalStateException(
                        "generated-code Dev Server network policy verification timed out");
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0
                    || !String.valueOf(expectedInternal).equalsIgnoreCase(output)) {
                throw new IllegalStateException(
                        "generated-code sandbox network internal policy is invalid: " + network);
            }
            sandboxMetrics.recordReadiness(metricResource, "ready");
        } catch (IOException exception) {
            sandboxMetrics.recordReadiness(metricResource, "failure");
            throw new IllegalStateException("failed to verify generated-code sandbox network policy", exception);
        } catch (InterruptedException exception) {
            sandboxMetrics.recordReadiness(metricResource, "interrupted");
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException(
                    "generated-code sandbox network policy verification was interrupted",
                    exception
            );
        } catch (RuntimeException exception) {
            sandboxMetrics.recordReadiness(metricResource, "failure");
            throw exception;
        }
    }

    @FunctionalInterface
    interface ProcessStarter {

        Process start(ProcessBuilder processBuilder) throws IOException;
    }
}
