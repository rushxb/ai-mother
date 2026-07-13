package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LocalAppArtifactLifecycleServiceTest {

    private Path tempDirectory;

    private Path outputRoot;
    private Path deployRoot;
    private LocalAppArtifactLifecycleService artifactService;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = createTestDirectory("artifact-lifecycle");
        outputRoot = tempDirectory.resolve("output");
        deployRoot = tempDirectory.resolve("deploy");
        artifactService = new LocalAppArtifactLifecycleService(
                outputRoot,
                deployRoot,
                false,
                mock(RobocopyDirectoryCopier.class)
        );
    }

    @Test
    void shouldCopyGeneratedSourceAtomicallyAndExcludeDerivedArtifacts() throws IOException {
        App sourceApp = app(11L, CodeGenTypeEnum.VUE_PROJECT, null);
        App targetApp = app(12L, CodeGenTypeEnum.VUE_PROJECT, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Files.createDirectories(sourceDirectory.resolve("src"));
        Files.writeString(sourceDirectory.resolve("src/App.vue"), "<template>你好</template>", StandardCharsets.UTF_8);
        Files.createDirectories(sourceDirectory.resolve("node_modules/package"));
        Files.writeString(sourceDirectory.resolve("node_modules/package/index.js"), "derived", StandardCharsets.UTF_8);
        Files.createDirectories(sourceDirectory.resolve("dist"));
        Files.writeString(sourceDirectory.resolve("dist/index.html"), "derived", StandardCharsets.UTF_8);
        Files.writeString(sourceDirectory.resolve(".ai-code-install.stamp"), "fingerprint", StandardCharsets.UTF_8);

        artifactService.copyGeneratedArtifact(sourceApp, targetApp);

        Path targetDirectory = outputRoot.resolve("vue_project_12");
        assertEquals("<template>你好</template>",
                Files.readString(targetDirectory.resolve("src/App.vue"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(targetDirectory.resolve("node_modules")));
        assertFalse(Files.exists(targetDirectory.resolve("dist")));
        assertFalse(Files.exists(targetDirectory.resolve(".ai-code-install.stamp")));
        assertFalse(hasStagingDirectory(outputRoot, ".artifact-copy-"));
    }

    @Test
    void shouldRejectExistingCopyTargetWithoutOverwritingIt() throws IOException {
        App sourceApp = app(21L, CodeGenTypeEnum.HTML, null);
        App targetApp = app(22L, CodeGenTypeEnum.HTML, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);
        Path targetDirectory = createGeneratedDirectory(targetApp);
        Files.writeString(targetDirectory.resolve("index.html"), "existing", StandardCharsets.UTF_8);

        BusinessException exception = assertThrows(BusinessException.class,
                () -> artifactService.copyGeneratedArtifact(sourceApp, targetApp));

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertEquals("existing", Files.readString(targetDirectory.resolve("index.html"), StandardCharsets.UTF_8));
    }

    @Test
    void shouldDelegateWindowsGeneratedCopyToRobocopyAdapter() throws Exception {
        RobocopyDirectoryCopier copier = mock(RobocopyDirectoryCopier.class);
        LocalAppArtifactLifecycleService windowsArtifactService =
                new LocalAppArtifactLifecycleService(outputRoot, deployRoot, true, copier);
        App sourceApp = app(23L, CodeGenTypeEnum.HTML, null);
        App targetApp = app(24L, CodeGenTypeEnum.HTML, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Files.writeString(sourceDirectory.resolve("index.html"), "source", StandardCharsets.UTF_8);

        windowsArtifactService.copyGeneratedArtifact(sourceApp, targetApp);

        verify(copier).copy(
                eq(sourceDirectory.toRealPath()),
                any(Path.class),
                eq(List.of(".git", ".idea", "node_modules", "dist", "target")),
                eq(List.of(
                        ".ai-code-install.stamp",
                        ".ai-code-critical.stamp",
                        ".ai-code-presentation.stamp"
                ))
        );
        assertTrue(Files.isDirectory(outputRoot.resolve("html_24")));
    }

    @Test
    void shouldRollbackDeploymentDirectoryToPreviousVersion() throws IOException {
        App sourceApp = app(31L, CodeGenTypeEnum.HTML, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Files.writeString(sourceDirectory.resolve("index.html"), "new", StandardCharsets.UTF_8);
        Path currentDeployment = deployRoot.resolve("Deploy31");
        Files.createDirectories(currentDeployment);
        Files.writeString(currentDeployment.resolve("index.html"), "old", StandardCharsets.UTF_8);

        DeploymentArtifactTransaction transaction =
                artifactService.prepareDeployment(sourceDirectory, "Deploy31");
        transaction.activate();
        assertEquals("new", Files.readString(currentDeployment.resolve("index.html"), StandardCharsets.UTF_8));

        transaction.rollback();

        assertEquals("old", Files.readString(currentDeployment.resolve("index.html"), StandardCharsets.UTF_8));
        assertFalse(hasStagingDirectory(deployRoot, ".deploy-staging-"));
        assertFalse(hasStagingDirectory(deployRoot, ".deploy-backup-"));
    }

    @Test
    void shouldCommitDeploymentDirectoryReplacement() throws IOException {
        App sourceApp = app(41L, CodeGenTypeEnum.HTML, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Files.writeString(sourceDirectory.resolve("index.html"), "new", StandardCharsets.UTF_8);
        Path currentDeployment = deployRoot.resolve("Deploy41");
        Files.createDirectories(currentDeployment);
        Files.writeString(currentDeployment.resolve("index.html"), "old", StandardCharsets.UTF_8);

        DeploymentArtifactTransaction transaction =
                artifactService.prepareDeployment(sourceDirectory, "Deploy41");
        transaction.activate();
        transaction.commit();

        assertEquals("new", Files.readString(currentDeployment.resolve("index.html"), StandardCharsets.UTF_8));
        assertFalse(hasStagingDirectory(deployRoot, ".deploy-staging-"));
        assertFalse(hasStagingDirectory(deployRoot, ".deploy-backup-"));
    }

    @Test
    void shouldRejectDeploymentOutsideOutputRootAndInvalidDeployKey() throws IOException {
        Path externalDirectory = tempDirectory.resolve("external");
        Files.createDirectories(externalDirectory);

        BusinessException outsideException = assertThrows(BusinessException.class,
                () -> artifactService.prepareDeployment(externalDirectory, "Deploy51"));
        BusinessException keyException = assertThrows(BusinessException.class,
                () -> artifactService.prepareDeployment(outputRoot, "../escape"));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), outsideException.getCode());
        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), keyException.getCode());
    }

    @Test
    void shouldRejectSymbolicLinkEscapingGeneratedDirectory() throws IOException {
        App sourceApp = app(61L, CodeGenTypeEnum.HTML, null);
        App targetApp = app(62L, CodeGenTypeEnum.HTML, null);
        Path sourceDirectory = createGeneratedDirectory(sourceApp);
        Path externalFile = tempDirectory.resolve("secret.txt");
        Files.writeString(externalFile, "secret", StandardCharsets.UTF_8);
        Path link = sourceDirectory.resolve("secret-link.txt");
        try {
            Files.createSymbolicLink(link, externalFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前环境不允许创建符号链接");
        }

        BusinessException exception = assertThrows(BusinessException.class,
                () -> artifactService.copyGeneratedArtifact(sourceApp, targetApp));

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        assertFalse(Files.exists(outputRoot.resolve("html_62")));
    }

    @Test
    void shouldRestoreGeneratedAndDeploymentArtifactsOnDeletionRollback() throws IOException {
        App app = app(71L, CodeGenTypeEnum.MULTI_FILE, "Deploy71");
        Path generatedDirectory = createGeneratedDirectory(app);
        Files.writeString(generatedDirectory.resolve("index.html"), "generated", StandardCharsets.UTF_8);
        Path deploymentDirectory = deployRoot.resolve("Deploy71");
        Files.createDirectories(deploymentDirectory);
        Files.writeString(deploymentDirectory.resolve("index.html"), "deployed", StandardCharsets.UTF_8);

        AppArtifactDeletionTransaction transaction = artifactService.prepareDeletion(app);
        transaction.activate();
        assertFalse(Files.exists(generatedDirectory));
        assertFalse(Files.exists(deploymentDirectory));

        transaction.rollback();

        assertEquals("generated", Files.readString(generatedDirectory.resolve("index.html"), StandardCharsets.UTF_8));
        assertEquals("deployed", Files.readString(deploymentDirectory.resolve("index.html"), StandardCharsets.UTF_8));
        assertFalse(hasStagingDirectory(outputRoot, ".artifact-delete-"));
        assertFalse(hasStagingDirectory(deployRoot, ".artifact-delete-"));
    }

    @Test
    void shouldCommitGeneratedAndDeploymentArtifactDeletion() throws IOException {
        App app = app(72L, CodeGenTypeEnum.HTML, "Deploy72");
        Path generatedDirectory = createGeneratedDirectory(app);
        Files.writeString(generatedDirectory.resolve("index.html"), "generated", StandardCharsets.UTF_8);
        Path deploymentDirectory = deployRoot.resolve("Deploy72");
        Files.createDirectories(deploymentDirectory);
        Files.writeString(deploymentDirectory.resolve("index.html"), "deployed", StandardCharsets.UTF_8);

        AppArtifactDeletionTransaction transaction = artifactService.prepareDeletion(app);
        transaction.activate();
        transaction.commit();

        assertFalse(Files.exists(generatedDirectory));
        assertFalse(Files.exists(deploymentDirectory));
        assertFalse(hasStagingDirectory(outputRoot, ".artifact-delete-"));
        assertFalse(hasStagingDirectory(deployRoot, ".artifact-delete-"));
    }

    @Test
    void shouldCommitDeletionWhenArtifactsDoNotExist() throws IOException {
        App app = app(73L, CodeGenTypeEnum.HTML, "Deploy73");

        AppArtifactDeletionTransaction transaction = artifactService.prepareDeletion(app);
        transaction.activate();
        transaction.commit();
        transaction.commit();

        assertFalse(hasStagingDirectory(outputRoot, ".artifact-delete-"));
        assertFalse(hasStagingDirectory(deployRoot, ".artifact-delete-"));
    }

    @Test
    void shouldRejectSymbolicLinkAsActiveDeletionDirectory() throws IOException {
        App app = app(74L, CodeGenTypeEnum.HTML, null);
        Files.createDirectories(outputRoot);
        Path externalDirectory = Files.createDirectories(tempDirectory.resolve("external-generated"));
        Path generatedLink = outputRoot.resolve("html_74");
        try {
            Files.createSymbolicLink(generatedLink, externalDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前环境不允许创建符号链接");
        }

        AppArtifactDeletionTransaction transaction = artifactService.prepareDeletion(app);
        BusinessException exception = assertThrows(BusinessException.class, transaction::activate);

        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), exception.getCode());
        assertTrue(Files.isSymbolicLink(generatedLink));
        assertFalse(hasStagingDirectory(outputRoot, ".artifact-delete-"));
    }

    private Path createGeneratedDirectory(App app) throws IOException {
        Path generatedDirectory = outputRoot.resolve(app.getCodeGenType() + "_" + app.getId());
        Files.createDirectories(generatedDirectory);
        return generatedDirectory;
    }

    private App app(Long id, CodeGenTypeEnum codeGenType, String deployKey) {
        App app = new App();
        app.setId(id);
        app.setCodeGenType(codeGenType.getValue());
        app.setDeployKey(deployKey);
        return app;
    }

    private boolean hasStagingDirectory(Path root, String prefix) throws IOException {
        if (!Files.isDirectory(root)) {
            return false;
        }
        try (var children = Files.list(root)) {
            return children.anyMatch(path -> path.getFileName().toString().startsWith(prefix));
        }
    }

    @AfterEach
    void tearDown() throws IOException {
        deleteRecursively(tempDirectory);
    }

    private Path createTestDirectory(String prefix) throws IOException {
        Path testRoot = Path.of("target", "test-temp").toAbsolutePath().normalize();
        Files.createDirectories(testRoot);
        return Files.createDirectories(testRoot.resolve(prefix + "-" + UUID.randomUUID()));
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.deleteIfExists(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
