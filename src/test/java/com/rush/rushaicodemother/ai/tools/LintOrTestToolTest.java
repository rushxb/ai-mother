package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.config.ProjectCommandProperties;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandExecutor;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LintOrTestToolTest {

    @Test
    void shouldDelegateAllowedPackageScriptToUnifiedExecutor() throws Exception {
        long appId = 920_001L;
        Path projectDirectory = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(projectDirectory);
        Files.writeString(
                projectDirectory.resolve("package.json"),
                "{\"scripts\":{\"lint\":\"eslint .\"}}",
                StandardCharsets.UTF_8
        );
        ProjectCommandExecutor executor = mock(ProjectCommandExecutor.class);
        ProjectCommandProperties properties = new ProjectCommandProperties();
        when(executor.executePnpmScript(
                projectDirectory,
                "lint",
                properties.getToolScriptTimeout(),
                "test-task-" + appId,
                "tool-check:lint"
        )).thenReturn(new ProjectCommandResult(
                ProjectCommandResult.Status.SUCCESS,
                "pnpm run lint",
                0,
                "lint passed",
                null
        ));
        LintOrTestTool tool = new LintOrTestTool(
                executor,
                properties,
                ToolPathSupportTestFixture.workspaceForApp(appId)
        );

        String report = tool.runProjectCheck("lint", "", appId);

        assertTrue(report.contains("lint passed"));
        verify(executor).executePnpmScript(
                projectDirectory,
                "lint",
                properties.getToolScriptTimeout(),
                "test-task-" + appId,
                "tool-check:lint"
        );
    }

    @Test
    void shouldSanitizeCommandOutputBeforeReturningItToTheModel() throws Exception {
        long appId = 920_002L;
        Path projectDirectory = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR, "vue_project_" + appId)
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(projectDirectory);
        Files.writeString(
                projectDirectory.resolve("package.json"),
                "{\"scripts\":{\"build\":\"vite build\"}}",
                StandardCharsets.UTF_8
        );
        ProjectCommandExecutor executor = mock(ProjectCommandExecutor.class);
        ProjectCommandProperties properties = new ProjectCommandProperties();
        when(executor.executePnpmScript(
                projectDirectory,
                "build",
                properties.getToolScriptTimeout(),
                "test-task-" + appId,
                "tool-check:build"
        )).thenReturn(new ProjectCommandResult(
                ProjectCommandResult.Status.FAILED,
                "pnpm run build",
                1,
                "src/App.vue:12:4 Cannot find module 'missing'\nprovider-api-key=secret-value",
                "Authorization: Bearer command-secret"
        ));
        LintOrTestTool tool = new LintOrTestTool(
                executor,
                properties,
                ToolPathSupportTestFixture.workspaceForApp(appId)
        );

        String report = tool.runProjectCheck("build", "", appId);

        assertFalse(report.contains("secret-value"));
        assertFalse(report.contains("command-secret"));
        assertTrue(report.contains("src/App.vue:12:4"));
        assertTrue(report.contains("Cannot find module 'missing'"));
    }
}
