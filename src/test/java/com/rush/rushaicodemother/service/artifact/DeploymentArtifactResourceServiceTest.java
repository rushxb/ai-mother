package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.SecurePathResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeploymentArtifactResourceServiceTest {

    private Path tempDirectory;
    private Path deployRoot;
    private DeploymentArtifactResourceService resourceService;

    @BeforeEach
    void setUp() throws IOException {
        Path testRoot = Path.of("target", "test-work", "deployment-resource").toAbsolutePath().normalize();
        Files.createDirectories(testRoot);
        tempDirectory = Files.createDirectory(testRoot.resolve(UUID.randomUUID().toString()));
        deployRoot = Files.createDirectory(tempDirectory.resolve("deploy"));

        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(tempDirectory.resolve("output"));
        storageProperties.setDeployRootDir(deployRoot);
        resourceService = new DeploymentArtifactResourceService(
                storageProperties,
                new DeploymentKeyPolicy(),
                new SecurePathResolver()
        );
    }

    @Test
    void shouldResolveOnlyFilesInsideCommittedDeploymentDirectory() throws IOException {
        Path deployment = Files.createDirectory(deployRoot.resolve("Deploy123"));
        Path expected = Files.writeString(deployment.resolve("index.html"), "deployed");

        Path resolved = resourceService.resolve("Deploy123", "index.html");

        assertEquals(expected.toRealPath(), resolved);
    }

    @Test
    void shouldRejectInvalidDeploymentKeysAndTraversal() throws IOException {
        Files.createDirectory(deployRoot.resolve("Deploy123"));

        assertThrows(IllegalArgumentException.class,
                () -> resourceService.resolve("../Deploy123", "index.html"));
        assertThrows(IllegalArgumentException.class,
                () -> resourceService.resolve("short", "index.html"));
        assertThrows(IllegalArgumentException.class,
                () -> resourceService.resolve("Deploy123", "../secret.txt"));
    }

    @Test
    void shouldRejectSymbolicLinksEscapingDeploymentScope() throws IOException {
        Path deployment = Files.createDirectory(deployRoot.resolve("Deploy123"));
        Path outsideFile = Files.writeString(tempDirectory.resolve("secret.txt"), "secret");
        Path link = deployment.resolve("secret-link.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.assumeTrue(false,
                    "The current environment does not permit symbolic-link creation: " + exception.getMessage());
        }

        assertThrows(IllegalArgumentException.class,
                () -> resourceService.resolve("Deploy123", "secret-link.txt"));
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempDirectory == null || !Files.exists(tempDirectory)) {
            return;
        }
        try (var paths = Files.walk(tempDirectory)) {
            for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(current);
            }
        }
    }
}
