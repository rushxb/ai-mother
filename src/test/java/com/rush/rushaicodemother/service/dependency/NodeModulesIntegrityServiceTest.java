package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NodeModulesIntegrityServiceTest {

    private Path tempDirectory;
    private Path projectDirectory;
    private Path pnpmDirectory;
    private DependencyInstallProperties properties;
    private ProjectProcessTerminator processTerminator;

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
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setMaxOutputLength(1024);
        processTerminator = mock(ProjectProcessTerminator.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        Thread.interrupted();
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldAcceptCompleteNodeModulesWhenViteRuntimeLoads() {
        NodeModulesIntegrityService service = createService(
                builder -> FakeProcess.completed(0, ""),
                false
        );

        assertTrue(service.isComplete(projectDirectory));
    }

    @Test
    void shouldEnforceRuntimeValidationTimeoutBeforeReadingCanBlockForever() {
        properties.setRuntimeValidationTimeout(Duration.ofMillis(30));
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        NodeModulesIntegrityService service = createService(builder -> process, false);

        assertFalse(service.isComplete(projectDirectory));

        verify(processTerminator).terminate(process);
        assertFalse(process.isAlive());
    }

    @Test
    void shouldDetectAndCleanCorruptedWindowsNativePackage() throws IOException {
        Path packageDirectory = createCorruptedRollupPackage();
        NodeModulesIntegrityService service = createService(
                builder -> FakeProcess.completed(0, ""),
                true
        );

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
        NodeModulesIntegrityService service = createService(
                builder -> FakeProcess.completed(0, ""),
                true
        );

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
        NodeModulesIntegrityService service = createService(
                builder -> FakeProcess.completed(0, ""),
                true
        );

        assertThrows(IOException.class, () -> service.cleanCorruptedNativePackages(projectDirectory));
        assertTrue(Files.exists(externalFile));
    }

    @Test
    void shouldRejectDeletionOutsidePnpmRoot() {
        NodeModulesIntegrityService service = createService(
                builder -> FakeProcess.completed(0, ""),
                true
        );
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
        NodeModulesIntegrityService service = createService(
                builder -> {
                    throw new AssertionError("不应启动 Vite 运行时校验");
                },
                false
        );

        assertFalse(service.isComplete(projectDirectory));
    }

    private Path createCorruptedRollupPackage() throws IOException {
        Path versionDirectory = Files.createDirectories(
                pnpmDirectory.resolve("@rollup+rollup-win32-x64-msvc@4.0.0")
        );
        return Files.createDirectories(
                versionDirectory.resolve("node_modules/@rollup/rollup-win32-x64-msvc")
        );
    }

    private NodeModulesIntegrityService createService(ProcessStarter starter, boolean windows) {
        return new NodeModulesIntegrityService(
                properties,
                processTerminator,
                starter,
                windows
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

    private static final class FakeProcess extends Process {

        private static final AtomicLong NEXT_PID = new AtomicLong(200_000);

        private final long pid = NEXT_PID.incrementAndGet();
        private final InputStream inputStream;
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive;
        private volatile int exitCode;

        private FakeProcess(boolean alive, int exitCode, String output) {
            this.alive = alive;
            this.exitCode = exitCode;
            this.inputStream = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
            if (!alive) {
                exitLatch.countDown();
            }
        }

        private static FakeProcess completed(int exitCode, String output) {
            return new FakeProcess(false, exitCode, output);
        }

        private static FakeProcess running() {
            return new FakeProcess(true, 143, "");
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return inputStream;
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return exitCode;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            finish(143);
        }

        @Override
        public Process destroyForcibly() {
            finish(137);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return pid;
        }

        private void finish(int code) {
            exitCode = code;
            alive = false;
            exitLatch.countDown();
        }
    }
}
