package com.rush.rushaicodemother.infrastructure.git;

import com.rush.rushaicodemother.config.GenerationCommitProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
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
                List.of("status", "--short"),
                "test"
        );

        ArgumentCaptor<ManagedProcessRequest> requestCaptor =
                ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(requestCaptor.capture());
        ManagedProcessRequest request = requestCaptor.getValue();
        assertTrue(request.command().contains("--literal-pathspecs"));
        assertEquals("0", request.environment().get("GIT_TERMINAL_PROMPT"));
        assertEquals("never", request.environment().get("GCM_INTERACTIVE"));
        assertFalse(request.redirectErrorStream());
        assertTrue(result.success());
    }
}
