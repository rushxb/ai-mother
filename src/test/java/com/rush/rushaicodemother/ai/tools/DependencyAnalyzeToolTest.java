package com.rush.rushaicodemother.ai.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DependencyAnalyzeToolTest {

    @Test
    void shouldReadPackageContextThroughBoundedWorkspaceService() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(930_101L)) {
            Files.writeString(
                    project.root().resolve("package.json"),
                    """
                            {
                              "dependencies": {"vue": "^3.5.0"},
                              "devDependencies": {"vite": "^7.0.0"},
                              "scripts": {"build": "vite build"}
                            }
                            """
            );
            DependencyAnalyzeTool tool = new DependencyAnalyzeTool(project.fileService());

            String result = tool.analyzeDependencyIssue(
                    "Cannot find module 'vue'",
                    "",
                    project.appId()
            );

            assertTrue(result.contains("已经出现在 package.json 中"));
            assertTrue(result.contains("dependencies 数量: 1"));
            assertTrue(result.contains("devDependencies 数量: 1"));
            assertTrue(result.contains("scripts 数量: 1"));
        }
    }
}
