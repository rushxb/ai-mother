package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.NodeToolchainProperties;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectCommandExecutorTest {

    private ProjectCommandProperties properties;
    private ProjectProcessTerminator processTerminator;
    private GenerationExecutionContextService executionContextService;
    private Path projectDirectory;

    @BeforeEach
    void setUp() throws Exception {
        projectDirectory = Files.createDirectories(
                Path.of("target", "test-temp", "project-command-executor").toAbsolutePath().normalize()
        );
        properties = new ProjectCommandProperties();
        properties.setIdleTimeout(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(10));
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setMaxOutputLength(1024);
        processTerminator = mock(ProjectProcessTerminator.class);
        executionContextService = mock(GenerationExecutionContextService.class);
        when(executionContextService.clampTimeout(any(), any())).thenAnswer(invocation -> invocation.getArgument(1));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void shouldExecutePnpmScriptWithControlledEnvironment() {
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        ProjectCommandExecutor executor = createExecutor(builder -> {
            capturedBuilder.set(builder);
            return FakeProcess.completed(0, "checked");
        });

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "lint",
                Duration.ofSeconds(1),
                "test"
        );

        assertTrue(result.success());
        assertEquals("checked", result.output());
        assertEquals("pnpm", capturedBuilder.get().command().getFirst());
        assertEquals("true", capturedBuilder.get().environment().get("CI"));
        assertEquals("false", capturedBuilder.get().environment().get("NPM_CONFIG_AUDIT"));
    }

    @Test
    void shouldDecodeUtf8OutputAcrossByteBoundaries() {
        String expectedOutput = "构建校验成功";
        byte[] utf8Bytes = expectedOutput.getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new ByteArrayInputStream(utf8Bytes) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        ProjectCommandExecutor executor = createExecutor(
                builder -> FakeProcess.completed(0, oneByteAtATime)
        );

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "build",
                Duration.ofSeconds(1),
                "test"
        );

        assertEquals(expectedOutput, result.output());
        assertFalse(result.output().contains("\uFFFD"));
    }

    @Test
    void shouldReturnNonZeroExitCode() {
        ProjectCommandExecutor executor = createExecutor(
                builder -> FakeProcess.completed(9, "build failed")
        );

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "build",
                Duration.ofSeconds(1),
                "test"
        );

        assertEquals(ProjectCommandResult.Status.FAILED, result.status());
        assertEquals(9, result.exitCode());
    }

    @Test
    void shouldTerminateProcessTreeOnTotalTimeout() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ProjectCommandExecutor executor = createExecutor(builder -> process);

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "build",
                Duration.ofMillis(40),
                "test"
        );

        assertEquals(ProjectCommandResult.Status.TIMED_OUT, result.status());
        assertFalse(process.isAlive());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateProcessTreeOnIdleTimeout() {
        properties.setIdleTimeout(Duration.ofMillis(40));
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ProjectCommandExecutor executor = createExecutor(builder -> process);

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "test",
                Duration.ofSeconds(1),
                "test"
        );

        assertEquals(ProjectCommandResult.Status.IDLE_TIMED_OUT, result.status());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldPreserveInterruptAndTerminateProcessTree() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ProjectCommandExecutor executor = createExecutor(builder -> process);
        Thread.currentThread().interrupt();

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "test",
                Duration.ofSeconds(1),
                "test"
        );

        assertEquals(ProjectCommandResult.Status.INTERRUPTED, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldApplyExecutionContextPolicyToTaskBoundCommand() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        Duration configuredTimeout = Duration.ofSeconds(5);
        Duration clampedTimeout = Duration.ofSeconds(2);
        when(executionContextService.clampTimeout("task-1", configuredTimeout)).thenReturn(clampedTimeout);
        when(executionContextService.shouldStop("task-1")).thenReturn(true);
        when(processExecutor.execute(any())).thenReturn(new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "pnpm run build",
                0,
                "ok",
                "",
                null
        ));
        ProjectCommandExecutor executor = new ProjectCommandExecutor(
                properties,
                processExecutor,
                executionContextService,
                createNodeToolchain()
        );

        ProjectCommandResult result = executor.executePnpmScript(
                projectDirectory,
                "build",
                configuredTimeout,
                "task-1",
                "test"
        );

        ArgumentCaptor<ManagedProcessRequest> requestCaptor = ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(requestCaptor.capture());
        ManagedProcessRequest request = requestCaptor.getValue();
        assertEquals(clampedTimeout, request.timeout());
        assertEquals(NodeProcessEnvironment.overrides(true), request.environment());
        assertEquals(NodeProcessEnvironment.variablesToRemove(), request.environmentVariablesToRemove());
        assertTrue(request.cancellationRequested().getAsBoolean());
        assertTrue(result.success());
        verify(executionContextService).assertCanContinue("task-1");
    }

    private ProjectCommandExecutor createExecutor(ProcessStarter starter) {
        ManagedProcessExecutor processExecutor = new ManagedProcessExecutor(processTerminator, starter);
        return new ProjectCommandExecutor(properties, processExecutor, executionContextService, createNodeToolchain());
    }

    private NodeToolchain createNodeToolchain() {
        return new NodeToolchain(new NodeToolchainProperties(), false);
    }

    private static final class FakeProcess extends Process {

        private static final AtomicLong NEXT_PID = new AtomicLong(200_000);

        private final long pid = NEXT_PID.incrementAndGet();
        private final InputStream inputStream;
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive;
        private volatile int exitCode;

        private FakeProcess(boolean alive, int exitCode, InputStream inputStream) {
            this.alive = alive;
            this.exitCode = exitCode;
            this.inputStream = inputStream;
            if (!alive) {
                exitLatch.countDown();
            }
        }

        private static FakeProcess completed(int exitCode, String output) {
            return completed(exitCode, new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        }

        private static FakeProcess completed(int exitCode, InputStream inputStream) {
            return new FakeProcess(false, exitCode, inputStream);
        }

        private static FakeProcess running() {
            return new FakeProcess(true, 143, InputStream.nullInputStream());
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
