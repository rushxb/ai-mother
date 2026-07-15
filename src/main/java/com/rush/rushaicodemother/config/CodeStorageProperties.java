package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.constant.AppConstant;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Generated-code, deployment-artifact and generation-snapshot storage roots.
 *
 * <p>This configuration is the dependency-injection boundary for filesystem roots. The legacy
 * {@link AppConstant} values are retained only as backward-compatible defaults for existing JVM
 * {@code -Dcode.*} startup parameters; business modules must consume this configuration instead
 * of reading static path constants directly.</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "code")
public class CodeStorageProperties {

    /** Root directory containing generated application workspaces. */
    @NotNull
    private Path outputRootDir = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR);

    /** Root directory containing immutable deployment views addressed by deployment key. */
    @NotNull
    private Path deployRootDir = Path.of(AppConstant.CODE_DEPLOY_ROOT_DIR);

    /** Root directory containing application-scoped generation snapshots. */
    @NotNull
    private Path snapshotRootDir = Path.of(AppConstant.CODE_SNAPSHOT_ROOT_DIR);

    /** Returns the normalized absolute generated-workspace root. */
    public Path outputRoot() {
        return normalizeRequired(outputRootDir, "code.output-root-dir");
    }

    /** Returns the normalized absolute deployment-artifact root. */
    public Path deployRoot() {
        return normalizeRequired(deployRootDir, "code.deploy-root-dir");
    }

    /** Returns the normalized absolute generation-snapshot root. */
    public Path snapshotRoot() {
        return normalizeRequired(snapshotRootDir, "code.snapshot-root-dir");
    }

    /**
     * Generated sources, deployment artifacts and snapshots must be pairwise isolated. Overlapping
     * roots would allow cleanup, deployment or rollback operations to cross storage responsibilities.
     */
    @AssertTrue(message = "Code output, deployment, and snapshot roots must be isolated")
    public boolean isStorageLayoutIsolated() {
        if (!isConfigured(outputRootDir) || !isConfigured(deployRootDir) || !isConfigured(snapshotRootDir)) {
            return false;
        }
        Path[] roots = {outputRoot(), deployRoot(), snapshotRoot()};
        for (int left = 0; left < roots.length; left++) {
            for (int right = left + 1; right < roots.length; right++) {
                if (overlaps(roots[left], roots[right])) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean overlaps(Path left, Path right) {
        return left.equals(right) || left.startsWith(right) || right.startsWith(left);
    }

    private boolean isConfigured(Path configuredPath) {
        return configuredPath != null && !configuredPath.toString().isBlank();
    }

    private Path normalizeRequired(Path configuredPath, String propertyName) {
        Path requiredPath = Objects.requireNonNull(configuredPath, propertyName + " must not be null");
        if (requiredPath.toString().isBlank()) {
            throw new IllegalStateException(propertyName + " must not be blank");
        }
        return requiredPath.toAbsolutePath().normalize();
    }
}
