package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationDeadlineExceededException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationRuntimeProperties;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import com.rush.rushaicodemother.security.workspace.GeneratedNodeWorkspaceValidator;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
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
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(true);
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        verify(commandExecutor, never()).install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class));
    }

    @Test
    void shouldRejectUnsafeProjectBeforeReusingExistingDependencies() throws IOException {
        Files.writeString(
                projectDirectory.resolve(".npmrc"),
                "registry=https://attacker.invalid/",
                StandardCharsets.UTF_8
        );
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(true);
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, result.status());
        assertTrue(result.errorDetail().contains("generated_workspace_forbidden_control_file:.npmrc"));
        verify(integrityService, never()).isComplete(any(), nullable(String.class));
        verify(commandExecutor, never()).install(
                any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class));
    }

    @Test
    void shouldReturnAfterFirstSuccessfulAndCompleteInstall() throws IOException {
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false, true);
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.success("installed"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        assertTrue(result.output().contains("installed"));
        verify(commandExecutor).install(
                eq(projectDirectory.toRealPath()), eq(false), eq(DependencyInstallMode.REUSE_IF_VALID),
                any(Duration.class), any(BooleanSupplier.class));
        verify(commandExecutor, never()).install(any(), eq(true), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class));
    }

    @Test
    void shouldFailWhenSuccessfulCommandsNeverProduceCompleteDependencies() throws IOException {
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false);
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.success("first"));
        when(commandExecutor.install(any(), eq(true), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.success("retry"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INTEGRITY_FAILED, result.status());
        verify(commandExecutor).install(
                eq(projectDirectory.toRealPath()), eq(false), eq(DependencyInstallMode.REUSE_IF_VALID),
                any(Duration.class), any(BooleanSupplier.class));
        verify(commandExecutor, times(2)).install(
                eq(projectDirectory.toRealPath()), eq(true), eq(DependencyInstallMode.REUSE_IF_VALID),
                any(Duration.class), any(BooleanSupplier.class));
        verify(integrityService, times(2)).cleanCorruptedNativePackages(projectDirectory.toRealPath());
    }

    @Test
    void shouldNotExposeCleanupExceptionDetails() throws IOException {
        String sensitiveDetail = "token=must-not-appear-in-result";
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false);
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.success("installed"));
        doThrow(new IOException(sensitiveDetail))
                .when(integrityService)
                .cleanCorruptedNativePackages(any());
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INTEGRITY_FAILED, result.status());
        assertEquals("清理损坏依赖失败，请检查项目目录权限和文件占用情况", result.errorDetail());
        assertFalse(result.errorDetail().contains(sensitiveDetail));
    }

    @Test
    void shouldTerminateOnlyCurrentProjectProcessesAfterPermissionFailure() throws IOException {
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false, true);
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.failed(
                DependencyInstallResult.Status.FAILED,
                "EPERM: operation not permitted",
                "exit code: 1"
        ));
        when(commandExecutor.install(any(), eq(true), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenReturn(DependencyInstallResult.success("recovered"));
        PnpmProjectDependencyInstaller installer = createInstaller();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertTrue(result.success());
        verify(processTerminator).terminateProjectProcesses(projectDirectory.toRealPath());
    }

    @Test
    void shouldPreserveInterruptAndStopBeforeExecutingInstall() {
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false);
        PnpmProjectDependencyInstaller installer = createInstaller();
        Thread.currentThread().interrupt();

        DependencyInstallResult result = installer.ensureInstalled(projectDirectory);

        assertEquals(DependencyInstallResult.Status.INTERRUPTED, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        verify(commandExecutor, never()).install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class));
    }

    @Test
    void shouldSerializeConcurrentInstallsForTheSameProject() throws Exception {
        properties.setMaxAttempts(1);
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger activeExecutions = new AtomicInteger();
        AtomicInteger maximumConcurrency = new AtomicInteger();
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
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
            verify(commandExecutor, times(2)).install(
                    eq(projectDirectory.toRealPath()), eq(false), eq(DependencyInstallMode.REUSE_IF_VALID),
                    any(Duration.class), any(BooleanSupplier.class));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void taskDeadlineMustBoundProjectLockWait() throws Exception {
        properties.setMaxAttempts(1);
        properties.setLockPolicyCheckInterval(Duration.ofMillis(10));
        when(integrityService.isComplete(any(), nullable(String.class))).thenReturn(false);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(commandExecutor.install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class)))
                .thenAnswer(invocation -> {
                    firstEntered.countDown();
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                    return DependencyInstallResult.failed(
                            DependencyInstallResult.Status.FAILED, "failed", "expected test failure");
                });

        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofMillis(100));
        runtimeProperties.setModelCallTimeout(Duration.ofMillis(50));
        runtimeProperties.setMinimumOperationTimeout(Duration.ofMillis(1));
        runtimeProperties.setFirstPreviewCompletionReserve(Duration.ofMillis(1));
        GenerationExecutionContextService contextService = new GenerationExecutionContextService(runtimeProperties);
        contextService.start("lock-deadline", 1L, 2L);
        PnpmProjectDependencyInstaller installer = new PnpmProjectDependencyInstaller(
                commandExecutor,
                integrityService,
                processTerminator,
                properties,
                contextService,
                new GeneratedNodeWorkspaceValidator(new GeneratedWorkspaceTrustPolicy())
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<DependencyInstallResult> first = executor.submit(() -> installer.ensureInstalled(projectDirectory));
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS));
            Future<DependencyInstallResult> waiting = executor.submit(
                    () -> installer.ensureInstalled(projectDirectory, "lock-deadline"));

            java.util.concurrent.ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> waiting.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof GenerationDeadlineExceededException);

            releaseFirst.countDown();
            first.get(2, TimeUnit.SECONDS);
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
        verify(commandExecutor, never()).install(any(), eq(false), any(DependencyInstallMode.class), any(Duration.class), any(BooleanSupplier.class));
    }

    private PnpmProjectDependencyInstaller createInstaller() {
        return new PnpmProjectDependencyInstaller(
                commandExecutor,
                integrityService,
                processTerminator,
                properties,
                new GenerationExecutionContextService(new GenerationRuntimeProperties()),
                new GeneratedNodeWorkspaceValidator(new GeneratedWorkspaceTrustPolicy())
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
