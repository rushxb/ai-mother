package com.rush.rushaicodemother.orchestration.benchmark.runtime;

import com.rush.rushaicodemother.config.GenerationBenchmarkBackendProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.infrastructure.process.GoToolchain;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.orchestration.template.ProjectTemplateCatalog;
import com.rush.rushaicodemother.orchestration.template.ProjectTemplateMaterializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GenerationBenchmarkBackendReadinessVerifierTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void disabledWorkerMustNotTouchTemplateWorkspaceOrToolchain() {
        GenerationBenchmarkBackendProperties properties = properties(false);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        GenerationBenchmarkBackendReadinessVerifier verifier = verifier(
                properties,
                materializer,
                processExecutor,
                goToolchain
        );

        assertDoesNotThrow(verifier::verify);

        verifyNoInteractions(materializer, processExecutor, goToolchain);
        assertFalse(Files.exists(properties.getWorkspaceRoot()));
    }

    @Test
    void enabledWorkerMustVerifyPackagedTemplateThroughManagedOfflineGoTest() throws Exception {
        GenerationBenchmarkBackendProperties properties = properties(true);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        AtomicReference<Path> materializedWorkspace = new AtomicReference<>();
        AtomicReference<ManagedProcessRequest> capturedRequest = new AtomicReference<>();
        when(goToolchain.goExecutable()).thenReturn("go.exe");
        when(materializer.materializeAtomically(
                eq(ProjectTemplateCatalog.GO_SQLITE_BACKEND),
                any(Path.class)
        )).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(1);
            materializedWorkspace.set(workspace);
            Files.createDirectories(workspace);
            return new ProjectTemplateMaterializer.MaterializationResult(workspace, 2, 32);
        });
        when(processExecutor.execute(any(ManagedProcessRequest.class))).thenAnswer(invocation -> {
            ManagedProcessRequest request = invocation.getArgument(0);
            capturedRequest.set(request);
            return completed(0, "ok");
        });
        GenerationBenchmarkBackendReadinessVerifier verifier = verifier(
                properties,
                materializer,
                processExecutor,
                goToolchain
        );

        assertDoesNotThrow(verifier::verify);

        ManagedProcessRequest request = capturedRequest.get();
        assertEquals(materializedWorkspace.get(), request.workingDirectory());
        assertEquals(
                List.of(
                        "go.exe", "test", "-mod=readonly", "-count=1", "-trimpath", "-buildvcs=false", "./..."
                ),
                request.command()
        );
        assertEquals(SandboxNetworkPolicy.NONE, request.networkPolicy());
        assertEquals("local", request.environment().get("GOTOOLCHAIN"));
        assertEquals("off", request.environment().get("GOPROXY"));
        assertEquals("off", request.environment().get("GOSUMDB"));
        assertFalse(Files.exists(materializedWorkspace.get()));
    }

    @Test
    void incompleteGoSdkMustFailStartupAndDeleteReadinessWorkspace() throws Exception {
        GenerationBenchmarkBackendProperties properties = properties(true);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        AtomicReference<Path> materializedWorkspace = new AtomicReference<>();
        when(goToolchain.goExecutable()).thenReturn("go");
        when(materializer.materializeAtomically(
                eq(ProjectTemplateCatalog.GO_SQLITE_BACKEND),
                any(Path.class)
        )).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(1);
            materializedWorkspace.set(workspace);
            Files.createDirectories(workspace);
            return new ProjectTemplateMaterializer.MaterializationResult(workspace, 2, 32);
        });
        when(processExecutor.execute(any(ManagedProcessRequest.class)))
                .thenReturn(completed(1, "找不到 Go 标准库"));
        GenerationBenchmarkBackendReadinessVerifier verifier = verifier(
                properties,
                materializer,
                processExecutor,
                goToolchain
        );

        IllegalStateException failure = assertThrows(IllegalStateException.class, verifier::verify);

        assertTrue(failure.getMessage().contains("完整 Go SDK"));
        assertFalse(Files.exists(materializedWorkspace.get()));
    }

    @Test
    void interruptedVerificationMustPreserveInterruptAndDeleteReadinessWorkspace() throws Exception {
        GenerationBenchmarkBackendProperties properties = properties(true);
        ProjectTemplateMaterializer materializer = mock(ProjectTemplateMaterializer.class);
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        GoToolchain goToolchain = mock(GoToolchain.class);
        AtomicReference<Path> materializedWorkspace = new AtomicReference<>();
        when(goToolchain.goExecutable()).thenReturn("go");
        when(materializer.materializeAtomically(
                eq(ProjectTemplateCatalog.GO_SQLITE_BACKEND),
                any(Path.class)
        )).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(1);
            materializedWorkspace.set(workspace);
            Files.createDirectories(workspace);
            return new ProjectTemplateMaterializer.MaterializationResult(workspace, 2, 32);
        });
        when(processExecutor.execute(any(ManagedProcessRequest.class))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            return new ManagedProcessResult(
                    ManagedProcessResult.Status.INTERRUPTED,
                    "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                    null,
                    "",
                    "",
                    "进程执行被中断"
            );
        });
        GenerationBenchmarkBackendReadinessVerifier verifier = verifier(
                properties,
                materializer,
                processExecutor,
                goToolchain
        );

        try {
            IllegalStateException failure = assertThrows(IllegalStateException.class, verifier::verify);
            assertTrue(failure.getMessage().contains("被中断"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        assertFalse(Files.exists(materializedWorkspace.get()));
    }

    private GenerationBenchmarkBackendReadinessVerifier verifier(
            GenerationBenchmarkBackendProperties properties,
            ProjectTemplateMaterializer materializer,
            ManagedProcessExecutor processExecutor,
            GoToolchain goToolchain
    ) {
        return new GenerationBenchmarkBackendReadinessVerifier(
                properties,
                materializer,
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                processExecutor,
                goToolchain
        );
    }

    private GenerationBenchmarkBackendProperties properties(boolean enabled) {
        GenerationBenchmarkBackendProperties properties = new GenerationBenchmarkBackendProperties();
        properties.setEnabled(enabled);
        properties.setWorkspaceRoot(temporaryRoot.resolve("backend-readiness"));
        return properties;
    }

    private ManagedProcessResult completed(int exitCode, String output) {
        return new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                exitCode,
                output,
                "",
                null
        );
    }
}
