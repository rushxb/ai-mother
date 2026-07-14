package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
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
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeModulesIntegrityServiceTest {

    private Path tempDirectory;
    private Path projectDirectory;
    private Path pnpmDirectory;
    private DependencyInstallProperties properties;
    private ManagedProcessExecutor processExecutor;
    private NodeToolchain nodeToolchain;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = createTestDirectory("node-integrity");
        projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path nodeModules = Files.createDirectories(projectDirectory.resolve("node_modules"));
        pnpmDirectory = Files.createDirectories(nodeModules.resolve(".pnpm"));
        Files.createDirectories(pnpmDirectory.resolve("vite@1.0.0"));
        Path binDirectory = Files.createDirectories(nodeModules.resolve(".bin"));
        Files.writeString(binDirectory.resolve("vite.cmd"), "@echo off", StandardCharsets.UTF_8);

        properties = new DependencyInstallProperties();
        properties.setRuntimeValidationTimeout(Duration.ofMillis(100));
        properties.setHeartbeatInterval(Duration.ofMillis(10));
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setMaxOutputLength(1024);
        processExecutor = mock(ManagedProcessExecutor.class);
        nodeToolchain = mock(NodeToolchain.class);
        when(nodeToolchain.nodeExecutable()).thenReturn("node");
        when(processExecutor.execute(any())).thenReturn(completed(0));
    }

    @AfterEach
    void tearDown() throws IOException {
        Thread.interrupted();
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldAcceptCompleteNodeModulesWhenViteRuntimeLoads() {
        AtomicReference<ManagedProcessRequest> capturedRequest = new AtomicReference<>();
        when(processExecutor.execute(any())).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            capturedRequest.set(request);
            return completed(0);
        });
        NodeModulesIntegrityService service = createService(false);

        assertTrue(service.isComplete(projectDirectory));
        assertEquals("node", capturedRequest.get().command().getFirst());
        assertEquals(properties.getRuntimeValidationTimeout(), capturedRequest.get().timeout());
        assertEquals(NodeProcessEnvironment.overrides(false), capturedRequest.get().environment());
        assertEquals(NodeProcessEnvironment.variablesToRemove(),
                capturedRequest.get().environmentVariablesToRemove());
    }

    @Test
    void shouldRejectRuntimeValidationTimeoutResult() {
        properties.setRuntimeValidationTimeout(Duration.ofMillis(30));
        properties.setHeartbeatInterval(Duration.ofMillis(5));
        when(processExecutor.execute(any())).thenReturn(new ManagedProcessResult(
                ManagedProcessResult.Status.TIMED_OUT,
                "node --input-type=module --eval ...",
                null,
                "",
                "",
                "外部进程执行超过总超时"
        ));
        NodeModulesIntegrityService service = createService(false);

        assertFalse(service.isComplete(projectDirectory));
    }

    @Test
    void shouldDetectAndCleanCorruptedWindowsNativePackage() throws IOException {
        Path packageDirectory = createCorruptedRollupPackage();
        NodeModulesIntegrityService service = createService(true);

        assertFalse(service.isComplete(projectDirectory));

        service.cleanCorruptedNativePackages(projectDirectory);
        assertFalse(Files.exists(packageDirectory));
    }

    @Test
    void shouldDeleteSymlinkWithoutTraversingExternalTarget() throws IOException {
        Path packageDirectory = createCorruptedRollupPackage();
        Path externalDirectory = Files.createDirectories(tempDirectory.resolve("external"));
        Path externalFile = Files.writeString(
                externalDirectory.resolve("keep.txt"),
                "keep",
                StandardCharsets.UTF_8
        );
        Path link = packageDirectory.resolve("external-link");
        try {
            Files.createSymbolicLink(link, externalDirectory);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前环境不允许创建符号链接");
        }
        NodeModulesIntegrityService service = createService(true);

        service.cleanCorruptedNativePackages(projectDirectory);

        assertFalse(Files.exists(packageDirectory));
        assertTrue(Files.exists(externalFile));
    }

    @Test
    void shouldRejectDeletionThroughSymlinkedAncestor() throws IOException {
        Path versionDirectory = Files.createDirectories(
                pnpmDirectory.resolve("@rollup+rollup-win32-x64-msvc@4.0.0")
        );
        Path externalNodeModules = Files.createDirectories(tempDirectory.resolve("external-node-modules"));
        Path externalPackage = Files.createDirectories(
                externalNodeModules.resolve("@rollup/rollup-win32-x64-msvc")
        );
        Path externalFile = Files.writeString(
                externalPackage.resolve("keep.txt"),
                "keep",
                StandardCharsets.UTF_8
        );
        try {
            Files.createSymbolicLink(versionDirectory.resolve("node_modules"), externalNodeModules);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前环境不允许创建符号链接");
        }
        NodeModulesIntegrityService service = createService(true);

        assertThrows(IOException.class, () -> service.cleanCorruptedNativePackages(projectDirectory));
        assertTrue(Files.exists(externalFile));
    }

    @Test
    void shouldRejectDeletionOutsidePnpmRoot() {
        NodeModulesIntegrityService service = createService(true);
        Path outside = pnpmDirectory.resolve("..").resolve("outside");

        assertThrows(
                IOException.class,
                () -> service.deleteNativePackageDirectory(pnpmDirectory, outside)
        );
    }

    @Test
    void shouldRejectViteSymlinkResolvingOutsideNodeModules() throws IOException {
        Path viteLauncher = projectDirectory.resolve("node_modules/.bin/vite.cmd");
        Files.delete(viteLauncher);
        Path outside = Files.writeString(
                tempDirectory.resolve("outside-vite.cmd"),
                "@echo off",
                StandardCharsets.UTF_8
        );
        try {
            Files.createSymbolicLink(viteLauncher, outside);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            assumeTrue(false, "当前环境不允许创建符号链接");
        }
        NodeModulesIntegrityService service = createService(false);

        assertFalse(service.isComplete(projectDirectory));
        verify(processExecutor, never()).execute(any());
    }

    private Path createCorruptedRollupPackage() throws IOException {
        Path versionDirectory = Files.createDirectories(
                pnpmDirectory.resolve("@rollup+rollup-win32-x64-msvc@4.0.0")
        );
        return Files.createDirectories(
                versionDirectory.resolve("node_modules/@rollup/rollup-win32-x64-msvc")
        );
    }

    private NodeModulesIntegrityService createService(boolean windows) {
        return new NodeModulesIntegrityService(
                properties,
                processExecutor,
                nodeToolchain,
                windows
        );
    }

    private ManagedProcessResult completed(int exitCode) {
        return new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "node --input-type=module --eval ...",
                exitCode,
                "",
                "",
                null
        );
    }

    private Path createTestDirectory(String prefix) throws IOException {
        Path root = Path.of("target", "test-temp").toAbsolutePath().normalize();
        Files.createDirectories(root);
        return Files.createDirectories(root.resolve(prefix + "-" + UUID.randomUUID()));
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
