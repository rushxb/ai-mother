package com.rush.rushaicodemother.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Runtime isolation settings for commands executed against AI-generated workspaces. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generated-code-sandbox")
public class GeneratedCodeSandboxProperties {

    @NotNull
    private Mode mode = Mode.HOST_LOCAL;

    @Valid
    private Container container = new Container();

    public enum Mode {
        HOST_LOCAL,
        CONTAINER
    }

    @Data
    public static class Container {

        private String runtime = "docker";
        private String image = "ai-code-mother/sandbox-node:1";
        private String workspaceMount = "/workspace";
        private String user = "1000:1000";
        private String dependencyNetwork = "bridge";
        private String devServerNetwork = "ai-code-sandbox-internal";
        private String previewGatewayNetwork = "ai-code-sandbox-preview-gateway";
        private boolean dependencyCacheEnabled = false;
        private String pnpmStoreVolume = "ai-code-mother-pnpm-store-v9";
        private String pnpmStoreMount = "/pnpm/store";
        private String memory = "1g";
        private String tmpfsSize = "256m";
        private String previewGatewayMemory = "128m";

        @DecimalMin("0.1")
        @DecimalMax("16.0")
        private double cpus = 1.5;

        @Min(16)
        @Max(4096)
        private int pidsLimit = 128;

        @DecimalMin("0.1")
        @DecimalMax("2.0")
        private double previewGatewayCpus = 0.25;

        @Min(16)
        @Max(512)
        private int previewGatewayPidsLimit = 64;

        private boolean readOnlyRoot = true;
        private boolean verifyOnStartup = true;
        private Duration startupVerificationTimeout = Duration.ofSeconds(10);
        private Duration activationTimeout = Duration.ofSeconds(10);
        private Duration cleanupTimeout = Duration.ofSeconds(5);

        @AssertTrue(message = "generated-code container sandbox configuration is invalid")
        public boolean isConfigurationValid() {
            return hasText(runtime)
                    && hasText(image)
                    && !containsControlCharacter(runtime)
                    && !containsControlCharacter(image)
                    && workspaceMount != null
                    && workspaceMount.startsWith("/")
                    && !"/".equals(workspaceMount)
                    && !workspaceMount.contains("..")
                    && isSafeNetworkName(dependencyNetwork)
                    && isSafeNetworkName(devServerNetwork)
                    && isSafeNetworkName(previewGatewayNetwork)
                    && isSafeVolumeName(pnpmStoreVolume)
                    && isSafeContainerMount(pnpmStoreMount)
                    && !containerPathsOverlap(pnpmStoreMount, workspaceMount)
                    && !containerPathsOverlap(pnpmStoreMount, "/tmp")
                    && isDockerSize(memory)
                    && isDockerSize(tmpfsSize)
                    && isDockerSize(previewGatewayMemory)
                    && isSafeUser(user)
                    && positive(startupVerificationTimeout)
                    && positive(activationTimeout)
                    && positive(cleanupTimeout);
        }

        private boolean hasText(String value) {
            return value != null && !value.isBlank();
        }

        private boolean positive(Duration value) {
            return value != null && !value.isZero() && !value.isNegative();
        }

        private boolean containsControlCharacter(String value) {
            return value.chars().anyMatch(Character::isISOControl);
        }

        private boolean isSafeNetworkName(String value) {
            if (!hasText(value) || !value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,62}")) {
                return false;
            }
            return !"host".equalsIgnoreCase(value)
                    && !"none".equalsIgnoreCase(value)
                    && !"default".equalsIgnoreCase(value);
        }

        private boolean isSafeVolumeName(String value) {
            return hasText(value)
                    && value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");
        }

        private boolean isSafeContainerMount(String value) {
            if (!hasText(value)
                    || !value.startsWith("/")
                    || "/".equals(value)
                    || value.endsWith("/")
                    || value.contains("//")
                    || value.contains("\\")
                    || value.contains(",")
                    || containsControlCharacter(value)) {
                return false;
            }
            for (String segment : value.substring(1).split("/")) {
                if (segment.isBlank() || ".".equals(segment) || "..".equals(segment)) {
                    return false;
                }
            }
            return true;
        }

        private boolean containerPathsOverlap(String first, String second) {
            if (!isSafeContainerMount(first) || !isSafeContainerMount(second)) {
                return true;
            }
            return first.equals(second)
                    || first.startsWith(second + "/")
                    || second.startsWith(first + "/");
        }

        private boolean isDockerSize(String value) {
            return hasText(value) && value.matches("[1-9][0-9]*(?:[bkmgBKMG])?");
        }

        private boolean isSafeUser(String value) {
            return value == null
                    || value.isBlank()
                    || value.matches("[1-9][0-9]{0,9}(?::[1-9][0-9]{0,9})?");
        }
    }
}
