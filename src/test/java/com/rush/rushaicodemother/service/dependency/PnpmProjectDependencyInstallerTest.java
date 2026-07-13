package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PnpmProjectDependencyInstallerTest {

    private Path tempDirectory;
    private Path projectDirectory;
    private DependencyInstallProperties properties;
    private PnpmInstallCommandExecutor commandExecutor;
    private NodeModulesIntegrityService integrityService;
    private ProjectProcessTerminator processTerminator;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = createTestDirectory("dependency-installer");
        projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Files.writeString(projectDirectory.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        properties = new DependencyInstallProperties();
        properties.setMaxAttempts(3);
        commandExecutor = mock(PnpmInstallCommandExecutor.class);
        integrityService = mock(NodeModulesIntegrityService.class);
        processTerminator = mock(ProjectProcessTerminator.class);
    }

    @AfterEach
    void tearDown() throws IOException {
        Thread.interrupted();
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldSkipInstallWhenDependenciesAreAlreadyComplete() {
        when(integrityService.isComplete(any())).thenReturn(true);
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        verify(commandExecutor, never()).install(any(), eq(false));
    }

    @Test
    void shouldReturnAfterFirstSuccessfulAndCompleteInstall() throws IOException {
        when(integrityService.isComplete(any())).thenReturn(false, true);
        when(commandExecutor.install(any(), eq(false)))
                .thenReturn(DependencyInstallResult.success("installed"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        assertTrue(result.output().contains("installed"));
        verify(commandExecutor).install(projectDirectory.toRealPath(), false);
        verify(commandExecutor, never()).install(any(), eq(true));
    }

    @Test
    void shouldFailWhenSuccessfulCommandsNeverProduceCompleteDependencies() throws IOException {
        when(integrityService.isComplete(any())).thenReturn(false);
        when(commandExecutor.install(any(), eq(false)))
                .thenReturn(DependencyInstallResult.success("first"));
        when(commandExecutor.install(any(), eq(true)))
                .thenReturn(DependencyInstallResult.success("retry"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INTEGRITY_FAILED, result.status());
        verify(commandExecutor).install(projectDirectory.toRealPath(), false);
        verify(commandExecutor, times(2)).install(projectDirectory.toRealPath(), true);
        verify(integrityService, times(2)).cleanCorruptedNativePackages(projectDirectory.toRealPath());
    }

    @Test
    void shouldTerminateOnlyCurrentProjectProcessesAfterPermissionFailure() throws IOException {
        when(integrityService.isComplete(any())).thenReturn(false, true);
        when(commandExecutor.install(any(), eq(false))).thenReturn(DependencyInstallResult.failed(
                DependencyInstallResult.Status.FAILED,
                "EPERM: operation not permitted",
                "exit code: 1"
        ));
        when(commandExecutor.install(any(), eq(true)))
                .thenReturn(DependencyInstallResult.success("recovered"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        verify(processTerminator).terminateProjectProcesses(projectDirectory.toRealPath());
    }

    @Test
    void shouldPreserveInterruptAndStopBeforeExecutingInstall() {
        when(integrityService.isComplete(any())).thenReturn(false);
        PnpmProjectDependencyInstaller installer = createInstaller();
        Thread.currentThread().interrupt();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INTERRUPTED, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        verify(commandExecutor, never()).install(any(), eq(false));
    }

    @Test
    void shouldSerializeConcurrentInstallsForTheSameProject() throws Exception {
        properties.setMaxAttempts(1);
        when(integrityService.isComplete(any())).thenReturn(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeExecutions = new AtomicInteger();
        AtomicInteger maximumConcurrency = new AtomicInteger();
        when(commandExecutor.install(any(), eq(false))).thenAnswer(invocation -> {
            int active = activeExecutions.incrementAndGet();
            maximumConcurrency.accumulateAndGet(active, Math::max);
            firstEntered.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            activeExecutions.decrementAndGet();
            return DependencyInstallResult.failed(
                    DependencyInstallResult.Status.FAILED,
                    "failed",
                    "expected test failure"
            );
        });
        PnpmProjectDependencyInstaller installer = createInstaller();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DependencyInstallResult> first = executor.submit(
                    () -> installer.ensureInstalled(projectDirectory)
            );
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<DependencyInstallResult> second = executor.submit(
                    () -> installer.ensureInstalled(projectDirectory)
            );
            Thread.sleep(100);
            assertEquals(1, activeExecutions.get());

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
            assertEquals(1, maximumConcurrency.get());
            verify(commandExecutor, times(2)).install(projectDirectory.toRealPath(), false);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldRejectMissingOrSymlinkedProjectBeforeInstalling() throws IOException {
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult missing = installer.ensureInstalled(tempDirectory.resolve("missing"));

        assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, missing.status());
        verify(commandExecutor, never()).install(any(), eq(false));
    }

    private PnpmProjectDependencyInstaller createInstaller() {
        return new PnpmProjectDependencyInstaller(
                commandExecutor,
                integrityService,
                processTerminator,
                properties
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
