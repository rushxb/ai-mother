package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionContextService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitCommandExecutorTest {

    @Test
    void shouldApplyNonInteractiveAndLiteralPathspecPolicy() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        when(processExecutor.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ManagedProcessResult(
                        ManagedProcessResult.Status.COMPLETED,
                        "git status",
                        0,
                        "ok",
                        "",
                        null
                )
        );
        GitCommandExecutor executor = new GitCommandExecutor(
                processExecutor,
                new GenerationCommitProperties(),
                mock(GenerationExecutionContextService.class)
        );

        GitCommandResult result = executor.execute(
                Path.of("."),
                List.of("-c", "user.useConfigOnly=true", "commit", "-m", "task-secret"),
                "test"
        );

        ArgumentCaptor<ManagedProcessRequest> requestCaptor =
                ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(requestCaptor.capture());
        ManagedProcessRequest request = requestCaptor.getValue();
        assertTrue(request.command().contains("--literal-pathspecs"));
        assertEquals("0", request.environment().get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", request.environment().get("GCM_INTERACTIVE"));
        assertEquals("1", request.environment().get("GIT_CONFIG_NOSYSTEM"));
        assertTrue(request.environment().get("GIT_CONFIG_GLOBAL")
                .endsWith(".ai-code-mother-git-config" + java.io.File.separator + "global"));
        assertTrue(request.environment().get("XDG_CONFIG_HOME")
                .endsWith(".ai-code-mother-git-config" + java.io.File.separator + "xdg"));
        assertFalse(request.redirectErrorStream());
        assertEquals(ManagedProcessOutputLogPolicy.SUMMARY, request.outputLogPolicy());
        assertEquals("git commit", request.displayCommand());
        assertFalse(request.displayCommand().contains("task-secret"));
        assertTrue(result.success());
    }

    @Test
    void taskScopedCommandMustUseRemainingDeadlineAndCancellationSignal() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        when(processExecutor.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
                new ManagedProcessResult(
                        ManagedProcessResult.Status.COMPLETED,
                        "git status",
                        0,
                        "ok",
                        "",
                        null
                )
        );
        GenerationCommitProperties properties = new GenerationCommitProperties();
        GenerationExecutionContextService executionContextService = mock(GenerationExecutionContextService.class);
        Duration remaining = Duration.ofMillis(40);
        AtomicBoolean stopRequested = new AtomicBoolean(false);
        when(executionContextService.clampTimeout("task-git", properties.getCommandTimeout()))
                .thenReturn(remaining);
        when(executionContextService.shouldStop("task-git"))
                .thenAnswer(ignored -> stopRequested.get());
        GitCommandExecutor executor = new GitCommandExecutor(
                processExecutor,
                properties,
                executionContextService
        );

        executor.execute(
                Path.of("."),
                List.of("status", "--short"),
                Map.of(),
                "test",
                "task-git"
        );

        ArgumentCaptor<ManagedProcessRequest> requestCaptor =
                ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(requestCaptor.capture());
        ManagedProcessRequest request = requestCaptor.getValue();
        assertEquals(remaining, request.timeout());
        stopRequested.set(true);
        assertTrue(request.cancellationRequested().getAsBoolean());
        verify(executionContextService).assertCanContinue("task-git");
    }
}
