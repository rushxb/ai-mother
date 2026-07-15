package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Resolves public deployment resources inside the isolated deployment-artifact root.
 *
 * <p>The HTTP layer supplies only a deployment key and a relative resource path. Filesystem root
 * selection, deployment-key validation, traversal protection, and symbolic-link containment stay
 * behind this module.</p>
 */
@Service
public class DeploymentArtifactResourceService {

    private final Path deployRoot;
    private final SecurePathResolver securePathResolver;
    private final DeploymentKeyPolicy deploymentKeyPolicy;

    public DeploymentArtifactResourceService(
            CodeStorageProperties storageProperties,
            DeploymentKeyPolicy deploymentKeyPolicy,
            SecurePathResolver securePathResolver
    ) {
        Objects.requireNonNull(storageProperties, "storageProperties must not be null");
        this.deployRoot = storageProperties.deployRoot();
        this.securePathResolver = Objects.requireNonNull(
                securePathResolver,
                "securePathResolver must not be null"
        );
        this.deploymentKeyPolicy = Objects.requireNonNull(
                deploymentKeyPolicy,
                "deploymentKeyPolicy must not be null"
        );
    }

    /** Resolves an existing regular file from one committed deployment directory. */
    public Path resolve(String deployKey, String relativePath) throws IOException {
        deploymentKeyPolicy.requireValid(deployKey);
        return securePathResolver.resolveRegularFile(deployRoot, deployKey, relativePath);
    }
}
