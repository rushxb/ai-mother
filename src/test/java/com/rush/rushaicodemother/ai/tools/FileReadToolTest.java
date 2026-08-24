package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import com.rush.rushaicodemother.orchestration.tool.ToolPublicFailureException;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionPolicyException;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FileReadToolTest {

    @Test
    void shouldReadUtf8ThroughBoundedWorkspaceModule() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_001L)) {
            Files.writeString(project.root().resolve("App.vue"), "你好");
            FileReadTool tool = new FileReadTool(project.fileService());

            assertTrue(tool.readFile("App.vue", project.appId()).contains("你好"));
        }
    }

    @Test
    void oversizedFileMustReturnStableErrorWithoutContent() throws Exception {
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxReadableFileBytes(1_024);
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_002L, properties)) {
            String secretContent = "secret-value-" + "x".repeat(2_048);
            Files.writeString(project.root().resolve("large.txt"), secretContent);
            FileReadTool tool = new FileReadTool(project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.readFile("large.txt", project.appId())
            );

            assertTrue(failure.publicMessage().contains("大小限制"));
            assertFalse(failure.publicMessage().contains("secret-value"));
        }
    }

    @Test
    void missingFileMustBeReportedAsProtocolFailure() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_009L)) {
            FileReadTool tool = new FileReadTool(project.fileService());

            ToolPublicFailureException failure = assertThrows(
                    ToolPublicFailureException.class,
                    () -> tool.readFile("missing.txt", project.appId())
            );

            assertTrue(failure.publicMessage().contains("文件不存在或不是文件"));
        }
    }

    @Test
    void emptyFileMustRemainAValidEmptySuccess() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_016L)) {
            Files.writeString(project.root().resolve("empty.txt"), "");
            FileReadTool tool = new FileReadTool(project.fileService());

            assertEquals("", tool.readFile("empty.txt", project.appId()));
        }
    }

    @Test
    void taskControlFailureMustPropagateWithoutProtocolConversion() {
        ToolWorkspaceFileService workspaceFileService = mock(ToolWorkspaceFileService.class);
        GenerationExecutionPolicyException policyFailure =
                new GenerationExecutionPolicyException("任务已取消");
        when(workspaceFileService.resolveFile(992_010L, "src/App.vue")).thenThrow(policyFailure);
        FileReadTool tool = new FileReadTool(workspaceFileService);

        GenerationExecutionPolicyException propagated = assertThrows(
                GenerationExecutionPolicyException.class,
                () -> tool.readFile("src/App.vue", 992_010L)
        );

        assertSame(policyFailure, propagated);
    }
}
