package com.rush.rushaicodemother.ai.tools;

import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolPathSupportTest {

    @Test
    void projectRootMustFollowCodeGenerationTypeFromBoundContext() {
        Map<CodeGenTypeEnum, String> expectedDirectoryNames = Map.of(
                CodeGenTypeEnum.VUE_PROJECT, "vue_project_990001",
                CodeGenTypeEnum.BACKEND_PROJECT, "backend_project_990001",
                CodeGenTypeEnum.FULL_STACK_PROJECT, "full_stack_project_990001"
        );

        expectedDirectoryNames.forEach((codeGenType, expectedDirectoryName) -> {
            ToolPathSupport pathSupport = ToolPathSupportTestFixture.forApp(990_001L, codeGenType);

            assertEquals(expectedDirectoryName, pathSupport.resolveProjectRoot(990_001L).getFileName().toString());
        });
    }

    @Test
    void missingExecutionContextMustFailInsteadOfFallingBackToVueProject() {
        ToolPathSupport pathSupport = new ToolPathSupport(new GenerationToolExecutionContextService());

        ToolInputException exception = assertThrows(
                ToolInputException.class,
                () -> pathSupport.resolveProjectRoot(990_002L)
        );

        assertTrue(exception.getMessage().contains("工具执行上下文不存在"));
    }

    @Test
    void invalidApplicationIdMustBeRejectedBeforePathResolution() {
        ToolPathSupport pathSupport = new ToolPathSupport(new GenerationToolExecutionContextService());

        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(null));
        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(0L));
        assertThrows(ToolInputException.class, () -> pathSupport.resolveProjectRoot(-1L));
    }

    @Test
    void traversalAndAbsolutePathsOutsideWorkspaceMustBeRejected() {
        long appId = 990_003L;
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.forApp(appId);
        Path outputRoot = Path.of(AppConstant.CODE_OUTPUT_ROOT_DIR).toAbsolutePath().normalize();
        Path outsidePath = outputRoot.getParent().resolve("outside-tool-workspace").normalize();

        assertThrows(ToolInputException.class, () -> pathSupport.resolvePath("../outside", appId));
        assertThrows(ToolInputException.class, () -> pathSupport.normalizeRelativePath("a/../../outside"));
        assertThrows(ToolInputException.class, () -> pathSupport.resolvePath(outsidePath.toString(), appId));
    }

    @Test
    void repeatedDotsInsideFileNameMustRemainValid() {
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.forApp(990_004L);

        String normalizedPath = pathSupport.normalizeRelativePath("src/foo..bar.ts");

        assertEquals("src/foo..bar.ts", normalizedPath);
        assertFalse(normalizedPath.contains("../"));
    }

    @Test
    void symbolicLinkSegmentsMustBeRejectedWhenPlatformSupportsLinks() throws Exception {
        long appId = 990_005L;
        ToolPathSupport pathSupport = ToolPathSupportTestFixture.forApp(appId);
        Path projectRoot = pathSupport.resolveProjectRoot(appId);
        Path realDirectory = projectRoot.resolve("real-directory");
        Path symbolicLink = projectRoot.resolve("linked-directory");
        Files.createDirectories(realDirectory);
        try {
            try {
                Files.createSymbolicLink(symbolicLink, realDirectory);
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                Assumptions.abort("当前平台不允许创建符号链接: " + e.getMessage());
            }

            assertThrows(
                    ToolInputException.class,
                    () -> pathSupport.resolvePath("linked-directory/file.txt", appId)
            );
        } finally {
            Files.deleteIfExists(symbolicLink);
            Files.deleteIfExists(realDirectory);
            Files.deleteIfExists(projectRoot);
        }
    }
}
