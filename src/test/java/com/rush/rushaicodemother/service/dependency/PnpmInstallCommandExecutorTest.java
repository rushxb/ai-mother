package com.rush.rushaicodemother.service.dependency;

import com.rush.rushaicodemother.infrastructure.process.ProcessStarter;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;

import com.rush.rushaicodemother.config.DependencyInstallProperties;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PnpmInstallCommandExecutorTest {

    private DependencyInstallProperties properties;
    private ProjectProcessTerminator processTerminator;
    private Path projectDirectory;

    @BeforeEach
    void setUp() throws Exception {
        projectDirectory = Files.createDirectories(
                Path.of("target", "test-temp", "command-executor").toAbsolutePath().normalize()
        );
        properties = new DependencyInstallProperties();
        properties.setCommandTimeout(Duration.ofSeconds(1));
        properties.setIdleTimeout(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(100));
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setMaxOutputLength(1024);
        processTerminator = mock(ProjectProcessTerminator.class);
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void shouldReturnSuccessAndBuildExpectedCommand() {
        FakeProcess process = FakeProcess.completed(0, "installed");
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        PnpmInstallCommandExecutor executor = createExecutor(builder -> {
            capturedBuilder.set(builder);
            return process;
        });

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertTrue(result.success());
        assertEquals("installed", result.output());
        assertEquals("pnpm", capturedBuilder.get().command().getFirst());
        assertFalse(capturedBuilder.get().command().contains("--force"));
        assertEquals("false", capturedBuilder.get().environment().get("NPM_CONFIG_AUDIT"));
    }

    @Test
    void shouldDecodeUtf8OutputAcrossByteBoundaries() {
        String expectedOutput = "依赖安装成功";
        byte[] utf8Bytes = expectedOutput.getBytes(StandardCharsets.UTF_8);
        InputStream oneByteAtATime = new ByteArrayInputStream(utf8Bytes) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
        PnpmInstallCommandExecutor executor = createExecutor(
                builder -> FakeProcess.completed(0, oneByteAtATime)
        );

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertTrue(result.success());
        assertEquals(expectedOutput, result.output());
        assertFalse(result.output().contains("\uFFFD"));
    }

    @Test
    void shouldReturnNonZeroExitCodeAsFailure() {
        PnpmInstallCommandExecutor executor = createExecutor(
                builder -> FakeProcess.completed(7, "install failed")
        );

        DependencyInstallResult result = executor.install(projectDirectory, true);

        assertEquals(DependencyInstallResult.Status.FAILED, result.status());
        assertTrue(result.errorDetail().contains("7"));
        assertTrue(executor.buildCommand(true).contains("--force"));
    }

    @Test
    void shouldTerminateProcessTreeBeforeReturningTotalTimeout() {
        properties.setCommandTimeout(Duration.ofMillis(40));
        properties.setIdleTimeout(Duration.ofSeconds(1));
        properties.setHeartbeatInterval(Duration.ofMillis(10));
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        PnpmInstallCommandExecutor executor = createExecutor(builder -> process);

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.TIMED_OUT, result.status());
        assertFalse(process.isAlive());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateProcessAfterIdleTimeout() {
        properties.setCommandTimeout(Duration.ofSeconds(1));
        properties.setIdleTimeout(Duration.ofMillis(40));
        properties.setHeartbeatInterval(Duration.ofMillis(10));
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        PnpmInstallCommandExecutor executor = createExecutor(builder -> process);

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.IDLE_TIMED_OUT, result.status());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldBoundCapturedOutput() {
        String output = "x".repeat(5000);
        PnpmInstallCommandExecutor executor = createExecutor(
                builder -> FakeProcess.completed(0, output)
        );

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertTrue(result.success());
        assertEquals(1024, result.output().length());
    }

    @Test
    void shouldPreserveInterruptAndTerminateRunningProcess() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        PnpmInstallCommandExecutor executor = createExecutor(builder -> process);
        Thread.currentThread().interrupt();

        DependencyInstallResult result = executor.install(projectDirectory, false);

        assertEquals(DependencyInstallResult.Status.INTERRUPTED, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldCancelRegisteredInstallAndReturnCancelledStatus() throws Exception {
        FakeProcess process = FakeProcess.running();
        CountDownLatch processStarted = new CountDownLatch(1);
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        PnpmInstallCommandExecutor executor = createExecutor(builder -> {
            processStarted.countDown();
            return process;
        });
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<DependencyInstallResult> future = worker.submit(
                    () -> executor.install(projectDirectory, false)
            );
            assertTrue(processStarted.await(1, TimeUnit.SECONDS));

            assertTrue(executor.cancel(projectDirectory));
            DependencyInstallResult result = future.get(2, TimeUnit.SECONDS);

            assertEquals(DependencyInstallResult.Status.CANCELLED, result.status());
            assertFalse(process.isAlive());
        } finally {
            worker.shutdownNow();
        }
    }

    private PnpmInstallCommandExecutor createExecutor(ProcessStarter starter) {
        return new PnpmInstallCommandExecutor(
                properties,
                processTerminator,
                starter,
                false
        );
    }

    private static final class FakeProcess extends Process {

        private static final AtomicLong NEXT_PID = new AtomicLong(100_000);

        private final long pid = NEXT_PID.incrementAndGet();
        private final InputStream inputStream;
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive;
        private volatile int exitCode;

        private FakeProcess(boolean alive, int exitCode, String output) {
            this(alive, exitCode, new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)));
        }

        private FakeProcess(boolean alive, int exitCode, InputStream inputStream) {
            this.alive = alive;
            this.exitCode = exitCode;
            this.inputStream = inputStream;
            if (!alive) {
                exitLatch.countDown();
            }
        }

        private static FakeProcess completed(int exitCode, String output) {
            return new FakeProcess(false, exitCode, output);
        }

        private static FakeProcess completed(int exitCode, InputStream inputStream) {
            return new FakeProcess(false, exitCode, inputStream);
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
