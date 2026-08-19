package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止工程角色布局重新散落回工作区主服务。 */
class GenerationWorkspaceLayoutArchitectureTest {

    @Test
    void workspaceServiceMustDelegateProjectLayoutToTheRegistry() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/rush/rushaicodemother/orchestration/workspace/GenerationWorkspaceService.java"));

        assertTrue(source.contains("layoutRegistry.resolve(codeGenType, canonicalRootPath)"));
        assertFalse(source.contains("switch (codeGenType)"));
        assertFalse(source.contains("codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT"));
    }
}
