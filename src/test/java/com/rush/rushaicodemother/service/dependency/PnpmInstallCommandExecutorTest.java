package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.NodeProcessEnvironment;
import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
import com.rush.rushaicodemother.security.workspace.GeneratedNodeWorkspaceValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PnpmInstallCommandExecutorTest {

    private DependencyInstallProperties properties;
    private ManagedProcessExecutor processExecutor;
    private ProjectProcessTerminator processTerminator;
    private NodeToolchain nodeToolchain;
    private Path projectDirectory;

    @BeforeEach
    void setUp() throws Exception {
        projectDirectory = Files.createDirectories(
                Path.of("target", "test-temp", "command-executor").toAbsolutePath().normalize()
        );
        Files.writeString(projectDirectory.resolve("package.json"), "{}", StandardCharsets.UTF_8);
        properties = new DependencyInstallProperties();
        properties.setCommandTimeout(Duration.ofSeconds(1));
        properties.setIdleTimeout(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(100));
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setMaxOutputLength(1024);
        processExecutor = mock(ManagedProcessExecutor.class);
        processTerminator = mock(ProjectProcessTerminator.class);
        nodeToolchain = mock(NodeToolchain.class);
        when(nodeToolchain.pnpmExecutable()).thenReturn("pnpm");
    }

    @Test
    void shouldReturnSuccessAndBuildExpectedRequest() throws Exception {
        AtomicReference<ManagedProcessRequest> capturedRequest = new AtomicReference<>();
        when(processExecutor.execute(any())).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            capturedRequest.set(request);
            return completed(0, "installed");
        });
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertTrue(result.success());
        assertEquals("installed", result.output());
        assertEquals("pnpm", capturedRequest.get().command().getFirst());
        assertFalse(capturedRequest.get().command().contains("--force"));
        assertTrue(capturedRequest.get().command().contains("--ignore-scripts"));
        assertTrue(capturedRequest.get().command().contains("--ignore-pnpmfile"));
        assertEquals(SandboxNetworkPolicy.DEPENDENCY_EGRESS,
                capturedRequest.get().networkPolicy());
        assertEquals(NodeProcessEnvironment.overrides(false), capturedRequest.get().environment());
        assertEquals(NodeProcessEnvironment.variablesToRemove(),
                capturedRequest.get().environmentVariablesToRemove());
        assertEquals(projectDirectory.toAbsolutePath().normalize().toRealPath(),
                capturedRequest.get().workingDirectory());
    }

    @Test
    void shouldReturnNonZeroExitCodeAsFailure() {
        when(processExecutor.execute(any())).thenReturn(completed(7, "install failed"));
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult result = executor.install(projectDirectory, true);

        assertEquals(DependencyInstallResult.Status.FAILED, result.status());
        assertTrue(result.errorDetail().contains("7"));
        assertTrue(executor.buildCommand(true).contains("--force"));
    }

    @Test
    void strictLockfileModeMustUseFrozenLockfile() {
        PnpmInstallCommandExecutor executor = createExecutor();

        assertTrue(executor.buildCommand(false, DependencyInstallMode.REFRESH_FROM_LOCKFILE)
                .contains("--frozen-lockfile"));
        assertFalse(executor.buildCommand(false, DependencyInstallMode.REFRESH_FROM_LOCKFILE)
                .contains("--no-frozen-lockfile"));
    }

    @Test
    void lockfileUpdateModeMustExplicitlyAllowLockfileMutation() {
        PnpmInstallCommandExecutor executor = createExecutor();

        assertTrue(executor.buildCommand(false, DependencyInstallMode.UPDATE_LOCKFILE)
                .contains("--no-frozen-lockfile"));
        assertFalse(executor.buildCommand(false, DependencyInstallMode.UPDATE_LOCKFILE)
                .contains("--frozen-lockfile"));
    }

    @Test
    void shouldMapManagedProcessTimeoutStatuses() {
        when(processExecutor.execute(any()))
                .thenReturn(failed(ManagedProcessResult.Status.TIMED_OUT, "总超时"))
                .thenReturn(failed(ManagedProcessResult.Status.IDLE_TIMED_OUT, "空闲超时"));
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult totalTimeout = executor.install(projectDirectory, false);
        DependencyInstallResult idleTimeout = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.TIMED_OUT, totalTimeout.status());
        assertEquals("总超时", totalTimeout.errorDetail());
        assertEquals(DependencyInstallResult.Status.IDLE_TIMED_OUT, idleTimeout.status());
        assertEquals("空闲超时", idleTimeout.errorDetail());
    }

    @Test
    void shouldNotExposeProcessStartExceptionDetails() {
        when(processExecutor.execute(any())).thenReturn(new ManagedProcessResult(
                ManagedProcessResult.Status.START_FAILED,
                "pnpm install",
                null,
                "",
                "",
                "provider-api-key=secret-value"
        ));
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.FAILED, result.status());
        assertEquals("执行 pnpm install 失败，请检查 Node.js、pnpm 和项目配置", result.errorDetail());
        assertFalse(result.errorDetail().contains("secret-value"));
    }

    @Test
    void shouldRejectInvalidProjectBeforeStartingProcess() {
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult result = executor.install(projectDirectory.resolve("missing"), false);

        assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, result.status());
        verify(processExecutor, never()).execute(any());
    }

    @Test
    void shouldRejectProjectRegistryRedirectBeforeStartingProcess() throws Exception {
        Path projectNpmConfig = projectDirectory.resolve(".npmrc");
        Files.writeString(
                projectNpmConfig,
                "registry=https://attacker.invalid/",
                StandardCharsets.UTF_8
        );
        when(processExecutor.execute(any())).thenReturn(completed(0, "must not execute"));
        PnpmInstallCommandExecutor executor = createExecutor();

        try {
            DependencyInstallResult result = executor.install(projectDirectory, false);

            assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, result.status());
            assertTrue(result.errorDetail().contains("generated_workspace_forbidden_control_file:.npmrc"));
            verify(processExecutor, never()).execute(any());
        } finally {
            Files.deleteIfExists(projectNpmConfig);
        }
    }

    @Test
    void shouldRejectUntrustedLockfileBeforeStartingProcess() throws Exception {
        Path lockfile = projectDirectory.resolve("pnpm-lock.yaml");
        Files.writeString(
                lockfile,
                """
                        lockfileVersion: '9.0'
                        packages:
                          vue@3.5.0:
                            resolution:
                              tarball: https://attacker.invalid/vue.tgz
                        """,
                StandardCharsets.UTF_8
        );
        PnpmInstallCommandExecutor executor = createExecutor();

        try {
            DependencyInstallResult result = executor.install(projectDirectory, false);

            assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, result.status());
            assertTrue(result.errorDetail().contains(
                    "generated_workspace_lockfile_external_resolution"));
            verify(processExecutor, never()).execute(any());
        } finally {
            Files.deleteIfExists(lockfile);
        }
    }

    @Test
    void shouldRejectCurrentLifecycleScriptBeforeStartingProcess() throws Exception {
        Files.writeString(
                projectDirectory.resolve("package.json"),
                "{\"scripts\":{\"postinstall\":\"node steal-secrets.js\"}}",
                StandardCharsets.UTF_8
        );
        when(processExecutor.execute(any())).thenReturn(completed(0, "must not execute"));
        PnpmInstallCommandExecutor executor = createExecutor();

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.INVALID_PROJECT, result.status());
        assertTrue(result.errorDetail().contains("executable_manifest_forbidden_lifecycle:postinstall"));
        verify(processExecutor, never()).execute(any());
    }

    @Test
    void shouldReserveProjectBeforeStartingProcessToAvoidDuplicateInstallRace() throws Exception {
        CountDownLatch firstEnteredExecutor = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger executionCount = new AtomicInteger();
        when(processExecutor.execute(any())).thenAnswer(invocation -> {
            executionCount.incrementAndGet();
            firstEnteredExecutor.countDown();
            assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
            return completed(0, "installed");
        });
        PnpmInstallCommandExecutor executor = createExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<DependencyInstallResult> first = worker.submit(
                    () -> executor.install(projectDirectory, false)
            );
            assertTrue(firstEnteredExecutor.await(1, TimeUnit.SECONDS));

            DependencyInstallResult duplicate = executor.install(projectDirectory, false);

            assertEquals(DependencyInstallResult.Status.FAILED, duplicate.status());
            assertEquals(1, executionCount.get());
            releaseFirst.countDown();
            assertTrue(first.get(2, TimeUnit.SECONDS).success());
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void shouldCancelRegisteredInstallAndReturnCancelledStatus() throws Exception {
        FakeProcess process = FakeProcess.running();
        CountDownLatch processStarted = new CountDownLatch(1);
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        when(processExecutor.execute(any())).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            request.lifecycle().onStarted(process);
            processStarted.countDown();
            while (!request.cancellationRequested().getAsBoolean()) {
                Thread.sleep(5);
            }
            request.lifecycle().onFinished(process);
            return failed(ManagedProcessResult.Status.INTERRUPTED, "外部进程已取消");
        });
        PnpmInstallCommandExecutor executor = createExecutor();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<DependencyInstallResult> future = worker.submit(
                    () -> executor.install(projectDirectory, false)
            );
            assertTrue(processStarted.await(1, TimeUnit.SECONDS));
            Path projectNpmConfig = projectDirectory.resolve(".npmrc");
            Files.writeString(
                    projectNpmConfig,
                    "registry=https://attacker.invalid/",
                    StandardCharsets.UTF_8
            );

            DependencyInstallResult result;
            try {
                assertTrue(executor.cancel(projectDirectory));
                result = future.get(2, TimeUnit.SECONDS);
            } finally {
                Files.deleteIfExists(projectNpmConfig);
            }

            assertEquals(DependencyInstallResult.Status.CANCELLED, result.status());
            assertFalse(process.isAlive());
            verify(processTerminator).terminate(process);
        } finally {
            worker.shutdownNow();
        }
    }

    private PnpmInstallCommandExecutor createExecutor() {
        return new PnpmInstallCommandExecutor(
                properties,
                processExecutor,
                processTerminator,
                nodeToolchain,
                new GeneratedNodeWorkspaceValidator(new GeneratedWorkspaceTrustPolicy())
        );
    }

    private ManagedProcessResult completed(int exitCode, String output) {
        return new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "pnpm install",
                exitCode,
                output,
                "",
                null
        );
    }

    private ManagedProcessResult failed(ManagedProcessResult.Status status, String errorDetail) {
        return new ManagedProcessResult(status, "pnpm install", null, "", "", errorDetail);
    }

    private static final class FakeProcess extends Process {

        private static final AtomicLong NEXT_PID = new AtomicLong(100_000);

        private final long pid = NEXT_PID.incrementAndGet();
        private volatile boolean alive = true;

        private static FakeProcess running() {
            return new FakeProcess();
        }

        @Override
        public OutputStream getOutputStream() {
            return new ByteArrayOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is still alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive = false;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
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
    }
}
