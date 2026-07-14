package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

            String result = tool.readFile("large.txt", project.appId());

            assertTrue(result.contains("大小限制"));
            assertFalse(result.contains("secret-value"));
        }
    }
}
