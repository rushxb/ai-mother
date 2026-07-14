package com.rush.rushaicodemother.ai.tools;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadMultipleFilesToolTest {

    @Test
    void nonPositiveCharacterLimitMustClampToOneCharacter() throws Exception {
        try (ToolWorkspaceTestProject project = ToolWorkspaceTestProject.create(992_005L)) {
            Files.writeString(project.root().resolve("sample.txt"), "abcdef");
            ReadMultipleFilesTool tool = new ReadMultipleFilesTool(project.fileService());

            String zeroLimitResult = tool.readMultipleFiles(List.of("sample.txt"), 0, project.appId());
            String negativeLimitResult = tool.readMultipleFiles(List.of("sample.txt"), -10, project.appId());

            assertTrue(zeroLimitResult.contains("a\n// 文件内容过长，已截断"));
            assertTrue(negativeLimitResult.contains("a\n// 文件内容过长，已截断"));
            assertFalse(zeroLimitResult.contains("abcdef"));
        }
    }
}
