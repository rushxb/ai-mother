package com.rush.rushaicodemother.service.artifact;

import com.rush.rushaicodemother.config.ArtifactLifecycleProperties;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessExecutor;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import com.rush.rushaicodemother.infrastructure.process.ManagedProcessResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RobocopyDirectoryCopierTest {

    @Test
    void shouldAcceptRobocopySuccessExitCodeAndUseUnicodeOutput() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        when(processExecutor.execute(any())).thenReturn(completed(7, "已复制"));
        RobocopyDirectoryCopier copier = new RobocopyDirectoryCopier(
                processExecutor,
                new ArtifactLifecycleProperties()
        );

        assertDoesNotThrow(() -> copier.copy(
                Path.of(".").toAbsolutePath().normalize(),
                Path.of("target", "copy-target").toAbsolutePath().normalize(),
                List.of("node_modules"),
                List.of(".ai-code-install.stamp")
        ));

        ArgumentCaptor<ManagedProcessRequest> requestCaptor =
                ArgumentCaptor.forClass(ManagedProcessRequest.class);
        verify(processExecutor).execute(requestCaptor.capture());
        ManagedProcessRequest request = requestCaptor.getValue();
        assertTrue(request.command().contains("/UNICODE"));
        assertTrue(request.command().contains("/XD"));
        assertTrue(request.command().contains("/XF"));
        assertEquals(StandardCharsets.UTF_16LE, request.outputCharset());
    }

    @Test
    void shouldRejectRobocopyFailureExitCode() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        when(processExecutor.execute(any())).thenReturn(completed(8, "复制失败"));
        RobocopyDirectoryCopier copier = new RobocopyDirectoryCopier(
                processExecutor,
                new ArtifactLifecycleProperties()
        );

        IOException exception = assertThrows(IOException.class, () -> copier.copy(
                Path.of(".").toAbsolutePath().normalize(),
                Path.of("target", "copy-target").toAbsolutePath().normalize(),
                List.of(),
                List.of()
        ));

        assertTrue(exception.getMessage().contains("exit code: 8"));
    }

    @Test
    void shouldPropagateInterruptSemantics() {
        ManagedProcessExecutor processExecutor = mock(ManagedProcessExecutor.class);
        when(processExecutor.execute(any())).thenReturn(new ManagedProcessResult(
                ManagedProcessResult.Status.INTERRUPTED,
                "robocopy",
                null,
                "",
                "",
                "interrupted"
        ));
        RobocopyDirectoryCopier copier = new RobocopyDirectoryCopier(
                processExecutor,
                new ArtifactLifecycleProperties()
        );

        assertThrows(InterruptedException.class, () -> copier.copy(
                Path.of(".").toAbsolutePath().normalize(),
                Path.of("target", "copy-target").toAbsolutePath().normalize(),
                List.of(),
                List.of()
        ));
    }

    private ManagedProcessResult completed(int exitCode, String output) {
        return new ManagedProcessResult(
                ManagedProcessResult.Status.COMPLETED,
                "robocopy",
                exitCode,
                output,
                "",
                null
        );
    }
}
