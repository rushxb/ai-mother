package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents AI file tools from bypassing the bounded workspace module. */
class AiFileToolWorkspaceBoundaryArchitectureTest {

    private static final Path TOOL_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "ai", "tools"
    );

    private static final List<String> WORKSPACE_TOOLS = List.of(
            "FileReadTool.java",
            "FileModifyTool.java",
            "FileWriteTool.java",
            "FileDeleteTool.java",
            "ReadMultipleFilesTool.java",
            "FileDirReadTool.java",
            "DependencyAnalyzeTool.java",
            "LintOrTestTool.java",
            "PackageManagerTool.java",
            "ProjectHealthCheckTool.java",
            "ProjectSearchTool.java"
    );

    private static final Map<String, String> FORBIDDEN_ACCESS = Map.of(
            "Files.readString(", "unbounded JDK file read",
            "FileUtil.readString(", "unbounded Hutool file read",
            "Files.walk(", "unbounded directory traversal",
            ".toFile()", "symbolic-link-following java.io.File access",
            "Files.exists(", "workspace-bypassing existence check",
            "Files.isRegularFile(", "workspace-bypassing file type check",
            "toolPathSupport.resolvePath(", "legacy direct workspace path resolution"
    );

    @Test
    void fileToolsMustUseToolWorkspaceFileService() throws IOException {
        for (String fileName : WORKSPACE_TOOLS) {
            Path sourceFile = TOOL_SOURCE_ROOT.resolve(fileName);
            String source = Files.readString(sourceFile);
            assertTrue(
                    source.contains("ToolWorkspaceFileService"),
                    () -> fileName + " must depend on ToolWorkspaceFileService"
            );
            for (Map.Entry<String, String> forbidden : FORBIDDEN_ACCESS.entrySet()) {
                assertTrue(
                        !source.contains(forbidden.getKey()),
                        () -> fileName + " contains " + forbidden.getValue() + ": " + forbidden.getKey()
                );
            }
        }
    }
}
