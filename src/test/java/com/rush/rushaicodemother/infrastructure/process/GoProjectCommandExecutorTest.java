package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.GoToolchainProperties;
import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.infrastructure.sandbox.SandboxNetworkPolicy;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoProjectCommandExecutorTest {

    @Test
    void shouldExecuteOfflineGoTestsWithTaskDeadlineAndCancellation() {
        ProjectCommandProperties properties = new ProjectCommandProperties();
        properties.setGoTestTimeout(Duration.ofMinutes(3));
        properties.setGoTestIdleTimeout(Duration.ofMinutes(2));
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        GenerationExecutionContextService contextService = mock(GenerationExecutionContextService.class);
        Duration clampedTimeout = Duration.ofSeconds(45);
        when(contextService.clampTimeout("task-go", properties.getGoTestTimeout()))
                .thenReturn(clampedTimeout);
        when(contextService.shouldStop("task-go")).thenReturn(true);
        when(processExecutor.execute(any())).thenReturn(new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "go test -mod=readonly -count=1 -trimpath -buildvcs=false ./...",
                0,
                "ok",
                "",
                null
        ));
        GoProjectCommandExecutor executor = new GoProjectCommandExecutor(
                properties,
                processExecutor,
                contextService,
                new GoToolchain(new GoToolchainProperties(), false)
        );

        ProjectCommandResult result = executor.executeTests(
                Path.of("target").toAbsolutePath().normalize(),
                "task-go",
                "test"
        );

        assertTrue(result.success());
        ArgumentCaptor<ManagedProcessRequest> captor = ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(captor.capture());
        ManagedProcessRequest request = captor.getValue();
        assertEquals(
                java.util.List.of(
                        "go", "test", "-mod=readonly", "-count=1", "-trimpath", "-buildvcs=false", "./..."
                ),
                request.command()
        );
        assertEquals(clampedTimeout, request.timeout());
        assertEquals(properties.getGoTestIdleTimeout(), request.idleTimeout());
        assertEquals(SandboxNetworkPolicy.NONE, request.networkPolicy());
        assertEquals(ManagedProcessOutputLogPolicy.SUMMARY, request.outputLogPolicy());
        assertEquals("off", request.environment().get("GOENV"));
        assertEquals("off", request.environment().get("GOPROXY"));
        assertEquals("off", request.environment().get("GOSUMDB"));
        assertEquals("local", request.environment().get("GOTOOLCHAIN"));
        assertEquals("0", request.environment().get("CGO_ENABLED"));
        assertTrue(request.environmentVariablesToRemove().contains("GOROOT"));
        Set<String> normalizedOverrides = request.environment().keySet().stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        assertTrue(request.environmentVariablesToRemove().stream()
                .map(name -> name.toUpperCase(Locale.ROOT))
                .noneMatch(normalizedOverrides::contains));
        assertTrue(request.cancellationRequested().getAsBoolean());
        verify(contextService).assertCanContinue("task-go");
    }
}
