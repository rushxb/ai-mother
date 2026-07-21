package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerProcessRunnerTest {

    private Path projectDirectory;

    private DevServerRuntimeProperties properties;
    private ViteLauncherResolver launcherResolver;
    private ProjectProcessTerminator processTerminator;
    private LoopbackReadinessProbe readinessProbe;

    @BeforeEach
    void setUp() throws Exception {
        projectDirectory = DevServerTestWorkspace.create("process-runner");
        properties = new DevServerRuntimeProperties();
        properties.setStartupTimeout(Duration.ofMillis(200));
        properties.setReadinessPollInterval(Duration.ofMillis(2));
        properties.setOutputDrainTimeout(Duration.ofMillis(50));
        launcherResolver = mock(ViteLauncherResolver.class);
        processTerminator = mock(ProjectProcessTerminator.class);
        readinessProbe = mock(LoopbackReadinessProbe.class);
        when(launcherResolver.resolve(projectDirectory, 5180, 11L))
                .thenReturn(List.of("fixed-node", "vite.js", "--host", "127.0.0.1",
                        "--port", "5180", "--strictPort"));
    }

    @AfterEach
    void tearDown() throws Exception {
        DevServerTestWorkspace.delete(projectDirectory);
    }

    @Test
    void shouldRequireTwoConsecutiveReadinessSuccessesAndUseDirectCommand() {
        FakeProcess process = FakeProcess.running();
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        ProcessStarter starter = builder -> {
            capturedBuilder.set(builder);
            return process;
        };
        when(readinessProbe.isReady(5180)).thenReturn(true, false, true, true);
        DevServerProcessRunner runner = runner(starter);

        DevServerProcessSession session = runner.start(
                projectDirectory, 5180, 11L, line -> { }, () -> false
        );

        assertSame(process, session.process());
        verify(readinessProbe, org.mockito.Mockito.times(4)).isReady(5180);
        ProcessBuilder builder = capturedBuilder.get();
        assertEquals(projectDirectory.toFile(), builder.directory());
        assertTrue(builder.redirectErrorStream());
        assertEquals(List.of("fixed-node", "vite.js", "--host", "127.0.0.1",
                "--port", "5180", "--strictPort"), builder.command());
        assertEquals("1", builder.environment().get("NO_UPDATE_NOTIFIER"));
        assertFalse(String.join(" ", builder.command()).toLowerCase().contains("cmd /c"));
        verify(processTerminator, never()).terminate(process);
    }

    @Test
    void shouldTerminateProcessWhenItExitsBeforeReadiness() {
        FakeProcess process = FakeProcess.completed(2);
        DevServerProcessRunner runner = runner(builder -> process);

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> runner.start(projectDirectory, 5180, 11L, line -> { }, () -> false)
        );

        assertEquals(DevServerStartException.Reason.PROCESS_EXITED, exception.reason());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateProcessOnStartupTimeout() {
        properties.setStartupTimeout(Duration.ofMillis(25));
        properties.setReadinessPollInterval(Duration.ofMillis(2));
        FakeProcess process = FakeProcess.running();
        when(readinessProbe.isReady(5180)).thenReturn(false);
        DevServerProcessRunner runner = runner(builder -> process);

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> runner.start(projectDirectory, 5180, 11L, line -> { }, () -> false)
        );

        assertEquals(DevServerStartException.Reason.STARTUP_TIMEOUT, exception.reason());
        verify(processTerminator).terminate(process);
    }

    @Test
    void explicitStartupTimeoutMustOverrideConfiguredDefault() {
        Duration taskScopedTimeout = Duration.ofMillis(20);
        FakeProcess process = FakeProcess.running();
        when(readinessProbe.isReady(5180)).thenReturn(false);
        DevServerProcessRunner runner = runner(builder -> process);

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> runner.start(
                        projectDirectory,
                        5180,
                        11L,
                        line -> { },
                        taskScopedTimeout,
                        () -> false
                )
        );

        assertEquals(DevServerStartException.Reason.STARTUP_TIMEOUT, exception.reason());
        assertTrue(exception.getMessage().contains(taskScopedTimeout.toString()));
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateProcessWhenCancellationIsRequested() {
        FakeProcess process = FakeProcess.running();
        DevServerProcessRunner runner = runner(builder -> process);

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> runner.start(projectDirectory, 5180, 11L, line -> { }, () -> true)
        );

        assertEquals(DevServerStartException.Reason.CANCELLED, exception.reason());
        verify(processTerminator).terminate(process);
        verify(readinessProbe, never()).isReady(5180);
    }

    @Test
    void interruptedStartupMustRestoreInterruptFlagAndTerminateProcess() {
        FakeProcess process = FakeProcess.running();
        when(readinessProbe.isReady(5180)).thenReturn(false);
        DevServerProcessRunner runner = runner(builder -> process);
        Thread.currentThread().interrupt();
        try {
            DevServerStartException exception = assertThrows(
                    DevServerStartException.class,
                    () -> runner.start(projectDirectory, 5180, 11L, line -> { }, () -> false)
            );

            assertEquals(DevServerStartException.Reason.INTERRUPTED, exception.reason());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(processTerminator).terminate(process);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void stopMustTerminateProcessAndAwaitOutputCompletion() {
        FakeProcess process = FakeProcess.running();
        DevServerProcessRunner runner = runner(builder -> process);
        DevServerProcessSession session = new DevServerProcessSession(
                projectDirectory,
                5180,
                process,
                CompletableFuture.completedFuture(null)
        );

        runner.stop(session);

        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldUseDevServerSandboxPlanAndCleanItExactlyOnce() {
        FakeProcess process = FakeProcess.running();
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        AtomicInteger cleanupCount = new AtomicInteger();
        AtomicInteger activationCount = new AtomicInteger();
        AtomicBoolean processStarted = new AtomicBoolean(false);
        AtomicBoolean planRecorded = new AtomicBoolean(false);
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(
                    com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest request,
                    Path normalizedWorkingDirectory
            ) {
                throw new AssertionError("short-lived sandbox path must not be used for a Dev Server");
            }

            @Override
            public SandboxProcessPlan prepareDevServer(
                    com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest request,
                    Path normalizedWorkingDirectory,
                    int hostPort
            ) {
                assertEquals(5180, hostPort);
                return new SandboxProcessPlan(
                        "container-test",
                        normalizedWorkingDirectory,
                        List.of("sandbox-launch", "5180"),
                        Map.of("SANDBOXED", "true"),
                        Set.of("NODE_OPTIONS"),
                        "container-123"
                );
            }

            @Override
            public void cleanup(SandboxProcessPlan plan) {
                assertEquals("container-123", plan.cleanupResourceId());
                cleanupCount.incrementAndGet();
            }

            @Override
            public void activate(SandboxProcessPlan plan) {
                assertEquals("container-123", plan.cleanupResourceId());
                activationCount.incrementAndGet();
            }
        };
        when(readinessProbe.isReady(5180)).thenReturn(true, true);
        DevServerProcessRunner runner = new DevServerProcessRunner(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                builder -> {
                    processStarted.set(true);
                    capturedBuilder.set(builder);
                    return process;
                },
                sandbox,
                GeneratedCodeSandboxMetricsCollector.noOp(),
                (appId, plan) -> {
                    assertEquals(11L, appId);
                    assertEquals("container-123", plan.cleanupResourceId());
                    assertFalse(processStarted.get(), "resource manifest must be durable before process start");
                    planRecorded.set(true);
                }
        );

        DevServerProcessSession session = runner.start(
                projectDirectory, 5180, 11L, line -> { }, () -> false
        );
        runner.stop(session);
        runner.awaitOutput(session);

        assertEquals(List.of("sandbox-launch", "5180"), capturedBuilder.get().command());
        assertEquals("true", capturedBuilder.get().environment().get("SANDBOXED"));
        assertEquals(1, activationCount.get());
        assertEquals(1, cleanupCount.get());
        assertTrue(planRecorded.get());
        verify(processTerminator).terminate(process);
    }

    private DevServerProcessRunner runner(ProcessStarter starter) {
        return new DevServerProcessRunner(
                properties,
                launcherResolver,
                processTerminator,
                readinessProbe,
                starter
        );
    }

    private static final class FakeProcess extends Process {

        private final InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        private volatile boolean alive;
        private volatile int exitCode;

        private FakeProcess(boolean alive, int exitCode) {
            this.alive = alive;
            this.exitCode = exitCode;
        }

        private static FakeProcess running() {
            return new FakeProcess(true, 143);
        }

        private static FakeProcess completed(int exitCode) {
            return new FakeProcess(false, exitCode);
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
        public int waitFor() {
            return exitCode;
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
            return exitCode;
        }

        @Override
        public void destroy() {
            alive = false;
            exitCode = 143;
        }

        @Override
        public Process destroyForcibly() {
            alive = false;
            exitCode = 137;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public long pid() {
            return 5252L;
        }
    }
}
