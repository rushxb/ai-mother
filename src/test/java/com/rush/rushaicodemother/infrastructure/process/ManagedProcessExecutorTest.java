package com.rush.rushaicodemother.infrastructure.process;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ManagedProcessExecutorTest {

    private ProjectProcessTerminator processTerminator;
    private Path workingDirectory;

    @BeforeEach
    void setUp() throws IOException {
        processTerminator = mock(ProjectProcessTerminator.class);
        workingDirectory = Files.createDirectories(
                Path.of("target", "test-temp", "managed-process-executor").toAbsolutePath().normalize()
        );
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void shouldCaptureSeparateUtf8StreamsAndBoundOutput() {
        FakeProcess process = FakeProcess.completed(
                0,
                oneByteAtATime("前缀-1234567890"),
                oneByteAtATime("错误输出")
        );
        ManagedProcessExecutor executor = executor(builder -> process);

        ManagedProcessResult result = executor.execute(requestBuilder()
                .maxOutputLength(10)
                .redirectErrorStream(false)
                .build());

        assertEquals(ManagedProcessResult.Status.COMPLETED, result.status());
        assertEquals("1234567890", result.stdout());
        assertEquals("错误输出", result.stderr());
        assertFalse(result.combinedOutput().contains("\uFFFD"));
    }

    @Test
    void shouldDecodeConfiguredUtf16Output() {
        String expected = "复制完成";
        FakeProcess process = FakeProcess.completed(
                0,
                new ByteArrayInputStream(expected.getBytes(StandardCharsets.UTF_16LE)),
                InputStream.nullInputStream()
        );
        ManagedProcessExecutor executor = executor(builder -> process);

        ManagedProcessResult result = executor.execute(requestBuilder()
                .outputCharset(StandardCharsets.UTF_16LE)
                .build());

        assertEquals(expected, result.stdout());
        assertFalse(result.stdout().contains("\uFFFD"));
    }

    @Test
    void shouldTerminateProcessTreeOnTotalTimeout() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ManagedProcessExecutor executor = executor(builder -> process);

        ManagedProcessResult result = executor.execute(requestBuilder()
                .timeout(Duration.ofMillis(40))
                .build());

        assertEquals(ManagedProcessResult.Status.TIMED_OUT, result.status());
        assertFalse(process.isAlive());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateProcessTreeOnIdleTimeout() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ManagedProcessExecutor executor = executor(builder -> process);

        ManagedProcessResult result = executor.execute(requestBuilder()
                .idleTimeout(Duration.ofMillis(40))
                .build());

        assertEquals(ManagedProcessResult.Status.IDLE_TIMED_OUT, result.status());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldPreserveInterruptAndTerminateProcessTree() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        ManagedProcessExecutor executor = executor(builder -> process);
        Thread.currentThread().interrupt();

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertEquals(ManagedProcessResult.Status.INTERRUPTED, result.status());
        assertTrue(Thread.currentThread().isInterrupted());
        assertFalse(process.isAlive());
    }

    @Test
    void shouldReturnStartFailureWithoutLeakingException() {
        ManagedProcessExecutor executor = executor(builder -> {
            throw new IOException("executable missing");
        });

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertEquals(ManagedProcessResult.Status.START_FAILED, result.status());
        assertEquals("executable missing", result.errorDetail());
    }

    private ManagedProcessRequest.ManagedProcessRequestBuilder requestBuilder() {
        return ManagedProcessRequest.builder()
                .workingDirectory(workingDirectory)
                .command(List.of("trusted-command", "--check"))
                .timeout(Duration.ofSeconds(1))
                .heartbeatInterval(Duration.ofMillis(10))
                .outputDrainTimeout(Duration.ofMillis(100))
                .maxOutputLength(1024)
                .redirectErrorStream(true)
                .logCategory("test-process")
                .logContext("managed-process-test");
    }

    private ManagedProcessExecutor executor(ProcessStarter processStarter) {
        return new ManagedProcessExecutor(processTerminator, processStarter);
    }

    private InputStream oneByteAtATime(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)) {
            @Override
            public synchronized int read(byte[] buffer, int offset, int length) {
                return super.read(buffer, offset, Math.min(length, 1));
            }
        };
    }

    private static final class FakeProcess extends Process {

        private static final AtomicLong NEXT_PID = new AtomicLong(300_000);

        private final long pid = NEXT_PID.incrementAndGet();
        private final InputStream inputStream;
        private final InputStream errorStream;
        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive;
        private volatile int exitCode;

        private FakeProcess(
                boolean alive,
                int exitCode,
                InputStream inputStream,
                InputStream errorStream
        ) {
            this.alive = alive;
            this.exitCode = exitCode;
            this.inputStream = inputStream;
            this.errorStream = errorStream;
            if (!alive) {
                exitLatch.countDown();
            }
        }

        private static FakeProcess completed(
                int exitCode,
                InputStream inputStream,
                InputStream errorStream
        ) {
            return new FakeProcess(false, exitCode, inputStream, errorStream);
        }

        private static FakeProcess running() {
            return new FakeProcess(
                    true,
                    143,
                    InputStream.nullInputStream(),
                    InputStream.nullInputStream()
            );
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
            return errorStream;
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
