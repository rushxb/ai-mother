package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessOutputLogPolicy;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;

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
                new GenerationCommitProperties()
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
}
