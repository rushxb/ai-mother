package com.rush.rushaicodemother.infrastructure.process;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.rush.rushaicodemother.infrastructure.sandbox.GeneratedCodeProcessSandbox;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxProcessPlan;
import com.rush.rushaicodemother.monitor.GeneratedCodeSandboxMetricsCollector;
import com.rush.rushaicodemother.infrastructure.sandbox.HostLocalGeneratedCodeProcessSandbox;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void activeGenerationTraceMustContainSandboxedProcessSpan() {
        Tracer tracer = mock(Tracer.class);
        Span parent = mock(Span.class);
        Span.Builder spanBuilder = mock(Span.Builder.class);
        Span processSpan = mock(Span.class);
        Tracer.SpanInScope scope = mock(Tracer.SpanInScope.class);
        when(tracer.currentSpan()).thenReturn(parent);
        when(tracer.spanBuilder()).thenReturn(spanBuilder);
        when(spanBuilder.name("generated_code.process.test-process")).thenReturn(spanBuilder);
        when(spanBuilder.kind(Span.Kind.CLIENT)).thenReturn(spanBuilder);
        when(spanBuilder.start()).thenReturn(processSpan);
        when(tracer.withSpan(processSpan)).thenReturn(scope);
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> FakeProcess.completed(
                        0, InputStream.nullInputStream(), InputStream.nullInputStream()),
                new HostLocalGeneratedCodeProcessSandbox(),
                GeneratedCodeSandboxMetricsCollector.noOp(),
                tracer
        );

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertTrue(result.exitedSuccessfully());
        verify(processSpan).tag("process.status", "completed");
        verify(processSpan).tag("process.success", true);
        verify(processSpan).end();
        verify(scope).close();
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
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedProcessExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ManagedProcessExecutor executor = executor(builder -> {
            throw new IOException("provider-api-key=secret-value");
        });

        ManagedProcessResult result;
        try {
            result = executor.execute(requestBuilder().build());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertEquals(ManagedProcessResult.Status.START_FAILED, result.status());
        assertEquals("外部进程启动失败，请检查运行环境和命令配置", result.errorDetail());
        assertFalse(result.errorDetail().contains("secret-value"));
        String loggedContent = appender.list.stream()
                .map(this::logEventText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(loggedContent.contains("secret-value"));
    }

    @Test
    void shouldTranslateSandboxPreparationFailureToStartFailure() {
        AtomicBoolean processStarted = new AtomicBoolean(false);
        GeneratedCodeProcessSandbox sandbox = (request, directory) -> {
            throw new IllegalStateException("container runtime unavailable");
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> {
                    processStarted.set(true);
                    return FakeProcess.completed(0, InputStream.nullInputStream(), InputStream.nullInputStream());
                },
                sandbox
        );

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertEquals(ManagedProcessResult.Status.START_FAILED, result.status());
        assertFalse(processStarted.get());
    }

    @Test
    void shouldActivateExposedPortBeforeLifecycleCallback() {
        List<String> events = new CopyOnWriteArrayList<>();
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(ManagedProcessRequest request, Path directory) {
                throw new AssertionError("暴露端口请求不应走普通沙箱准备路径");
            }

            @Override
            public SandboxProcessPlan prepareDevServer(
                    ManagedProcessRequest request,
                    Path directory,
                    int hostPort
            ) {
                assertEquals(5180, hostPort);
                events.add("prepare");
                return new SandboxProcessPlan(
                        "test-sandbox",
                        directory,
                        request.command(),
                        request.environment(),
                        request.environmentVariablesToRemove(),
                        "container-5180"
                );
            }

            @Override
            public void activate(SandboxProcessPlan plan) {
                events.add("activate");
            }

            @Override
            public void cleanup(SandboxProcessPlan plan) {
                events.add("cleanup");
            }
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> {
                    events.add("start");
                    return FakeProcess.completed(
                            0,
                            InputStream.nullInputStream(),
                            InputStream.nullInputStream()
                    );
                },
                sandbox
        );

        ManagedProcessResult result = executor.execute(requestBuilder()
                .networkPolicy(SandboxNetworkPolicy.RUNTIME_INTERNAL)
                .exposedPort(5180)
                .lifecycle(new ManagedProcessLifecycle() {
                    @Override
                    public void onStarted(Process process) {
                        events.add("lifecycle-started");
                    }

                    @Override
                    public void onFinished(Process process) {
                        events.add("lifecycle-finished");
                    }
                })
                .build());

        assertTrue(result.exitedSuccessfully());
        assertEquals(
                List.of("prepare", "start", "activate", "lifecycle-started", "lifecycle-finished", "cleanup"),
                events
        );
    }

    @Test
    void sandboxActivationTimeMustCountTowardTotalTimeout() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(ManagedProcessRequest request, Path directory) {
                return new SandboxProcessPlan(
                        "test-sandbox",
                        directory,
                        request.command(),
                        request.environment(),
                        request.environmentVariablesToRemove(),
                        "activation-timeout"
                );
            }

            @Override
            public void activate(SandboxProcessPlan plan) {
                LockSupport.parkNanos(Duration.ofMillis(60).toNanos());
            }
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> process,
                sandbox
        );

        ManagedProcessResult result = executor.execute(requestBuilder()
                .timeout(Duration.ofMillis(40))
                .build());

        assertEquals(ManagedProcessResult.Status.TIMED_OUT, result.status());
        assertFalse(process.isAlive());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldTerminateAndCleanProcessWhenSandboxActivationFails() {
        FakeProcess process = FakeProcess.running();
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroyForcibly();
            return true;
        });
        AtomicBoolean cleaned = new AtomicBoolean(false);
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(ManagedProcessRequest request, Path directory) {
                return new SandboxProcessPlan(
                        "test-sandbox",
                        directory,
                        request.command(),
                        request.environment(),
                        request.environmentVariablesToRemove(),
                        "container-activation-failure"
                );
            }

            @Override
            public void activate(SandboxProcessPlan plan) {
                throw new IllegalStateException("沙箱激活失败");
            }

            @Override
            public void cleanup(SandboxProcessPlan plan) {
                cleaned.set(true);
            }
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> process,
                sandbox
        );

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertEquals(ManagedProcessResult.Status.START_FAILED, result.status());
        assertFalse(process.isAlive());
        assertTrue(cleaned.get());
        verify(processTerminator).terminate(process);
    }

    @Test
    void shouldRejectInvalidExposedPortBeforeStartingProcess() {
        AtomicBoolean processStarted = new AtomicBoolean(false);
        ManagedProcessExecutor executor = executor(builder -> {
            processStarted.set(true);
            return FakeProcess.completed(0, InputStream.nullInputStream(), InputStream.nullInputStream());
        });

        assertThrows(
                IllegalArgumentException.class,
                () -> executor.execute(requestBuilder()
                        .networkPolicy(SandboxNetworkPolicy.RUNTIME_INTERNAL)
                        .exposedPort(65_536)
                        .build())
        );
        assertFalse(processStarted.get());
    }

    @Test
    void shouldNotReplaceSuccessfulResultWhenSandboxCleanupFails() {
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(ManagedProcessRequest request, Path directory) {
                return new SandboxProcessPlan(
                        "test-sandbox",
                        directory,
                        request.command(),
                        request.environment(),
                        request.environmentVariablesToRemove(),
                        "cleanup-resource"
                );
            }

            @Override
            public void cleanup(SandboxProcessPlan plan) {
                throw new IllegalStateException("cleanup failed");
            }
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> FakeProcess.completed(
                        0,
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream()
                ),
                sandbox
        );

        ManagedProcessResult result = executor.execute(requestBuilder().build());

        assertEquals(ManagedProcessResult.Status.COMPLETED, result.status());
        assertEquals(0, result.exitCode());
    }

    @Test
    void shouldRecordSandboxExecutionAndCleanupMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GeneratedCodeSandboxMetricsCollector metrics =
                new GeneratedCodeSandboxMetricsCollector(registry);
        GeneratedCodeProcessSandbox sandbox = new GeneratedCodeProcessSandbox() {
            @Override
            public SandboxProcessPlan prepare(ManagedProcessRequest request, Path directory) {
                return new SandboxProcessPlan(
                        "container",
                        directory,
                        request.command(),
                        request.environment(),
                        request.environmentVariablesToRemove(),
                        "container-123"
                );
            }
        };
        ManagedProcessExecutor executor = new ManagedProcessExecutor(
                processTerminator,
                builder -> FakeProcess.completed(
                        0,
                        InputStream.nullInputStream(),
                        InputStream.nullInputStream()
                ),
                sandbox,
                metrics
        );

        ManagedProcessResult result = executor.execute(requestBuilder()
                .logCategory("project-command")
                .build());

        assertEquals(ManagedProcessResult.Status.COMPLETED, result.status());
        assertEquals(1, registry.find("generated_code_sandbox_executions_total")
                .tag("backend", "container")
                .tag("workload", "project-command")
                .tag("status", "completed")
                .counter()
                .count(), 0.001);
        assertEquals(1, registry.find("generated_code_sandbox_cleanup_total")
                .tag("backend", "container")
                .tag("status", "success")
                .counter()
                .count(), 0.001);
    }

    @Test
    void shouldRemoveEnvironmentVariablesAfterApplyingOverrides() {
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        ManagedProcessExecutor executor = executor(builder -> {
            capturedBuilder.set(builder);
            return FakeProcess.completed(
                    0,
                    InputStream.nullInputStream(),
                    InputStream.nullInputStream()
            );
        });

        executor.execute(requestBuilder()
                .environment(Map.of(
                        "NODE_OPTIONS", "--require malicious-bootstrap.js",
                        "SAFE_VARIABLE", "safe-value"
                ))
                .environmentVariablesToRemove(Set.of("NODE_OPTIONS"))
                .build());

        assertFalse(capturedBuilder.get().environment().containsKey("NODE_OPTIONS"));
        assertEquals("safe-value", capturedBuilder.get().environment().get("SAFE_VARIABLE"));
    }

    @Test
    void hostLocalMustKeepDeterministicGoEnvironmentAndRemoveHostGoConfiguration() {
        AtomicReference<ProcessBuilder> capturedBuilder = new AtomicReference<>();
        ManagedProcessExecutor executor = executor(builder -> {
            capturedBuilder.set(builder);
            return FakeProcess.completed(
                    0,
                    InputStream.nullInputStream(),
                    InputStream.nullInputStream()
            );
        });
        Map<String, String> environment = new LinkedHashMap<>(GoProcessEnvironment.overrides());
        environment.put("GOROOT", "C:\\untrusted-go-root");
        environment.put("GOPATH", "C:\\untrusted-go-path");
        environment.put("GOPRIVATE", "private.example.com");

        executor.execute(requestBuilder()
                .environment(environment)
                .environmentVariablesToRemove(GoProcessEnvironment.variablesToRemove())
                .build());

        Map<String, String> effectiveEnvironment = capturedBuilder.get().environment();
        GoProcessEnvironment.overrides().forEach(
                (name, value) -> assertEquals(value, effectiveEnvironment.get(name), name));
        assertFalse(effectiveEnvironment.containsKey("GOROOT"));
        assertFalse(effectiveEnvironment.containsKey("GOPATH"));
        assertFalse(effectiveEnvironment.containsKey("GOPRIVATE"));
    }

    @Test
    void shouldRedactSensitiveCommandArgumentsInResultAndLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(ManagedProcessExecutor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        ManagedProcessExecutor executor = executor(builder -> FakeProcess.completed(
                0,
                InputStream.nullInputStream(),
                InputStream.nullInputStream()
        ));

        ManagedProcessResult result;
        try {
            result = executor.execute(requestBuilder()
                    .command(List.of(
                            "trusted-command",
                            "--token", "secret-value",
                            "--api-key=other-secret"
                    ))
                    .build());
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }

        assertFalse(result.command().contains("secret-value"));
        assertFalse(result.command().contains("other-secret"));
        assertTrue(result.command().contains("***"));
        String loggedContent = appender.list.stream()
                .map(this::logEventText)
                .reduce("", (left, right) -> left + "\n" + right);
        assertFalse(loggedContent.contains("secret-value"));
        assertFalse(loggedContent.contains("other-secret"));
    }

    private String logEventText(ILoggingEvent event) {
        String throwableMessage = event.getThrowableProxy() == null
                ? ""
                : event.getThrowableProxy().getMessage();
        return event.getFormattedMessage() + "\n" + throwableMessage;
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
