package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.config.GeneratedCodeSandboxProperties;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Fails startup when container mode is configured but its runtime or immutable image is absent. */
@Component
@ConditionalOnProperty(name = "app.generated-code-sandbox.mode", havingValue = "container")
public class ContainerSandboxReadinessVerifier implements SmartInitializingSingleton {

    private final GeneratedCodeSandboxProperties.Container properties;
    private final GeneratedCodeSandboxMetricsCollector sandboxMetrics;
    private final ProcessStarter processStarter;

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

    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isVerifyOnStartup()) {
            return;
        }
        verifyAvailable(
                "image",
                "image",
                "inspect",
                properties.getImage(),
                "generated-code container sandbox runtime or configured image is unavailable"
        );
        if (properties.isDependencyCacheEnabled()) {
            verifyAvailable(
                    "pnpm_store_volume",
                    "volume",
                    "inspect",
                    properties.getPnpmStoreVolume(),
                    "generated-code pnpm store volume is unavailable"
            );
        }
        verifyAvailable(
                "dependency_network",
                "network",
                "inspect",
                properties.getDependencyNetwork(),
                "generated-code dependency egress network is unavailable"
        );
        verifyAvailable(
                "dev_server_network",
                "network",
                "inspect",
                properties.getDevServerNetwork(),
                "generated-code Dev Server internal network is unavailable"
        );
        verifyAvailable(
                "preview_gateway_network",
                "network",
                "inspect",
                properties.getPreviewGatewayNetwork(),
                "generated-code preview gateway network is unavailable"
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

    private void verifyAvailable(
            String metricResource,
            String resourceType,
            String operation,
            String resource,
            String unavailableMessage
    ) {
        Process process = null;
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    properties.getRuntime(), resourceType, operation, resource)
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

    private void verifyNetworkInternalPolicy(
            String network,
            boolean expectedInternal,
            String metricResource
    ) {
        Process process = null;
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
