package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.process.GoToolchain;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.process.ProjectProcessTerminator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ManagedGenerationBenchmarkBackendRuntimeTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void healthySessionMustUseDisposableCopyAndReleaseAllOwnedResources() throws Exception {
        Path source = createBackendProject();
        int port = findAvailablePort();
        GenerationBenchmarkBackendProperties properties = properties(port);
        GenerationBenchmarkBackendPortAllocator allocator =
                new GenerationBenchmarkBackendPortAllocator(properties);
        GenerationBenchmarkBackendHttpProbe probe = mock(GenerationBenchmarkBackendHttpProbe.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        ProjectProcessTerminator processTerminator = mock(ProjectProcessTerminator.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        FakeProcess process = new FakeProcess();
        AtomicReference<ManagedProcessRequest> capturedRequest = new AtomicReference<>();
        when(goToolchain.goExecutable()).thenReturn("go.exe");
        when(probe.awaitHealthy(process, port)).thenReturn(BackendRuntimeObservation.passed());
        when(processExecutor.execute(any(ManagedProcessRequest.class))).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            capturedRequest.set(request);
            request.lifecycle().onStarted(process);
            process.waitFor();
            request.lifecycle().onFinished(process);
            return new ManagedProcessResult(
                    ManagedProcessResult.Status.COMPLETED,
                    "go run -mod=readonly ./cmd/server",
                    0,
                    "",
                    "",
                    null
            );
        });
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroy();
            return true;
        });
        ManagedGenerationBenchmarkBackendRuntime runtime =
                new ManagedGenerationBenchmarkBackendRuntime(
                        properties,
                        allocator,
                        probe,
                        processExecutor,
                        processTerminator,
                        new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                        goToolchain
                );

        BackendRuntimeHandle handle = runtime.start(source);
        ManagedProcessRequest request = capturedRequest.get();

        assertTrue(handle.healthy());
        assertEquals(port, handle.port());
        assertEquals(port, request.exposedPort());
        assertEquals("127.0.0.1:" + port, request.environment().get("SERVER_ADDR"));
        assertTrue(request.environment().get("DATABASE_DSN").contains("mode=memory"));
        assertEquals("off", request.environment().get("GOPROXY"));
        assertEquals("off", request.environment().get("GOSUMDB"));
        assertNotEquals(source, request.workingDirectory());
        assertTrue(Files.isDirectory(request.workingDirectory()));
        assertTrue(Files.isRegularFile(source.resolve("go.mod")));

        Path stagedProject = request.workingDirectory();
        handle.close();
        handle.close();

        assertFalse(process.isAlive());
        assertFalse(Files.exists(stagedProject));
        try (GenerationBenchmarkBackendPortAllocator.PortLease reused = allocator.reserve()) {
            assertEquals(port, reused.port());
        }
    }

    @Test
    void interruptedProbeMustPropagateCancellationAfterReleasingOwnedResources() throws Exception {
        Path source = createBackendProject();
        int port = findAvailablePort();
        GenerationBenchmarkBackendProperties properties = properties(port);
        GenerationBenchmarkBackendPortAllocator allocator =
                new GenerationBenchmarkBackendPortAllocator(properties);
        GenerationBenchmarkBackendHttpProbe probe = mock(GenerationBenchmarkBackendHttpProbe.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        ProjectProcessTerminator processTerminator = mock(ProjectProcessTerminator.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        FakeProcess process = new FakeProcess();
        AtomicReference<ManagedProcessRequest> capturedRequest = new AtomicReference<>();
        when(goToolchain.goExecutable()).thenReturn("go.exe");
        when(probe.awaitHealthy(process, port)).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("后端运行时探测被中断");
        });
        when(processExecutor.execute(any(ManagedProcessRequest.class))).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            capturedRequest.set(request);
            request.lifecycle().onStarted(process);
            process.waitFor();
            request.lifecycle().onFinished(process);
            return new ManagedProcessResult(
                    ManagedProcessResult.Status.COMPLETED,
                    "go run -mod=readonly ./cmd/server",
                    0,
                    "",
                    "",
                    null
            );
        });
        when(processTerminator.terminate(process)).thenAnswer(invocation -> {
            process.destroy();
            return true;
        });
        ManagedGenerationBenchmarkBackendRuntime runtime =
                new ManagedGenerationBenchmarkBackendRuntime(
                        properties,
                        allocator,
                        probe,
                        processExecutor,
                        processTerminator,
                        new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                        goToolchain
                );

        IllegalStateException failure;
        try {
            failure = assertThrows(IllegalStateException.class, () -> runtime.start(source));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }

        assertEquals("后端运行时探测被中断", failure.getMessage());
        assertFalse(process.isAlive());
        Path stagedProject = capturedRequest.get().workingDirectory();
        awaitDeletion(stagedProject);
        assertFalse(Files.exists(stagedProject));
        try (GenerationBenchmarkBackendPortAllocator.PortLease reused = allocator.reserve()) {
            assertEquals(port, reused.port());
        }
    }

    private Path createBackendProject() throws IOException {
        Path source = Files.createDirectories(temporaryRoot.resolve("source-backend"));
        Path server = Files.createDirectories(source.resolve("cmd").resolve("server"));
        Files.writeString(source.resolve("go.mod"), "module generated-backend\n\ngo 1.23\n", StandardCharsets.UTF_8);
        Files.writeString(server.resolve("main.go"), "package main\nfunc main() {}\n", StandardCharsets.UTF_8);
        return source.toAbsolutePath().normalize();
    }

    private GenerationBenchmarkBackendProperties properties(int port) {
        GenerationBenchmarkBackendProperties properties =
                new GenerationBenchmarkBackendProperties();
        properties.setWorkspaceRoot(temporaryRoot.resolve("runtime-copies"));
        properties.setPortRangeStart(port);
        properties.setPortRangeEnd(port);
        properties.setStartupTimeout(Duration.ofSeconds(2));
        properties.setProcessTimeout(Duration.ofSeconds(10));
        properties.setHeartbeatInterval(Duration.ofSeconds(1));
        properties.setOutputDrainTimeout(Duration.ofMillis(100));
        properties.setShutdownTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress("127.0.0.1", 0));
            return socket.getLocalPort();
        }
    }

    private void awaitDeletion(Path path) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (Files.exists(path) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static final class FakeProcess extends Process {

        private final CountDownLatch exitLatch = new CountDownLatch(1);
        private volatile boolean alive = true;

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
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int waitFor() throws InterruptedException {
            exitLatch.await();
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            return exitLatch.await(timeout, unit);
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("进程仍在运行");
            }
            return 0;
        }

        @Override
        public void destroy() {
            alive = false;
            exitLatch.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
