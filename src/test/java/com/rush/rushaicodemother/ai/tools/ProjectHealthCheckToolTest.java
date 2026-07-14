package com.rush.rushaicodemother.ai.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectHealthCheckToolTest {

    @Test
    void shouldCheckProjectThroughBoundedWorkspaceWithoutExposingAbsolutePath() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(930_102L)) {
            Files.createDirectories(project.root().resolve("src/router"));
            Files.writeString(
                    project.root().resolve("package.json"),
                    """
                            {
                              "scripts": {"dev": "vite", "build": "vite build"},
                              "dependencies": {"vue": "^3.5.0", "vue-router": "^4.5.0"},
                              "devDependencies": {"vite": "^7.0.0", "@vitejs/plugin-vue": "^6.0.0"}
                            }
                            """
            );
            Files.writeString(
                    project.root().resolve("vite.config.ts"),
                    "import vue from '@vitejs/plugin-vue'; export default { base: './', plugins: [vue()], resolve: { alias: { '@': '/src' } } };"
            );
            Files.writeString(project.root().resolve("src/main.ts"), "import './App.vue';");
            Files.writeString(project.root().resolve("src/App.vue"), "<template><main>ok</main></template>");
            Files.writeString(
                    project.root().resolve("src/router/index.ts"),
                    "import { createWebHashHistory } from 'vue-router'; createWebHashHistory();"
            );
            Files.writeString(project.root().resolve("index.html"), "<div id=\"app\"></div>");
            ProjectHealthCheckTool tool = new ProjectHealthCheckTool(project.fileService());

            String result = tool.checkProjectHealth("", project.appId());

            assertTrue(result.contains("项目健康检查: ."));
            assertTrue(result.contains("阻断问题: 0"));
            assertTrue(result.contains("告警问题: 0"));
            assertFalse(result.contains(project.root().toString()));
        }
    }
}
