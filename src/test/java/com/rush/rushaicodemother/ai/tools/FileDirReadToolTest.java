package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.AiToolWorkspaceProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileDirReadToolTest {

    @Test
    void directoryResultMustReportTruncationAndHideIgnoredPaths() throws Exception {
        AiToolWorkspaceProperties properties = new AiToolWorkspaceProperties();
        properties.setMaxDirectoryEntries(2);
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_006L, properties)) {
            Files.writeString(project.root().resolve("a.txt"), "a");
            Files.writeString(project.root().resolve("b.txt"), "b");
            Files.writeString(project.root().resolve("c.txt"), "c");
            Files.createDirectories(project.root().resolve("node_modules/pkg"));
            Files.writeString(project.root().resolve("node_modules/pkg/secret.js"), "secret");
            FileDirReadTool tool = new FileDirReadTool(project.fileService());

            String result = tool.readDir(null, project.appId());

            assertTrue(result.contains("已截断"));
            assertFalse(result.contains("node_modules"));
            assertFalse(result.contains("secret.js"));
        }
    }
}
