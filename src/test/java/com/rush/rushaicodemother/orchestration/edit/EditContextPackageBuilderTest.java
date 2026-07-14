package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.EditLocatorProperties;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditContextPackageBuilderTest {

    @Test
    void shouldEnforceSingleFileAndTotalCharacterBudgets() throws Exception {
        Path root = cleanTestRoot("context-budgets");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/First.ts"), "a".repeat(2_000));
        Files.writeString(root.resolve("src/Second.ts"), "b".repeat(1_000));
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxSingleFileChars(1_024);
        properties.setMaxTotalContextChars(1_500);
        EditContextPackageBuilder builder = builder(properties);

        EditContextPackage contextPackage = builder.build(workspace(root), List.of(
                candidate("src/First.ts"), candidate("src/Second.ts")
        ));

        assertEquals(1_500, contextPackage.totalChars());
        assertEquals(1_024, contextPackage.fileContents().get("src/First.ts").length());
        assertEquals(476, contextPackage.fileContents().get("src/Second.ts").length());
        assertEquals(List.of("src/First.ts", "src/Second.ts"),
                contextPackage.fileContents().keySet().stream().toList());
    }

    @Test
    void shouldSkipOversizedCandidateInsteadOfLoadingThenTruncating() throws Exception {
        Path root = cleanTestRoot("oversized-context");
        Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/Large.ts"), "x".repeat(1_025));
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxReadableFileBytes(1_024);

        EditContextPackage contextPackage = builder(properties).build(
                workspace(root), List.of(candidate("src/Large.ts"))
        );

        assertTrue(contextPackage.isEmpty());
        assertFalse(contextPackage.fileContents().containsKey("src/Large.ts"));
    }

    @Test
    void shouldBuildStableBoundedProjectIndexAndExcludeGeneratedDirectories() throws Exception {
        Path root = cleanTestRoot("project-index");
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve("dist"));
        Files.createDirectories(root.resolve("target"));
        Files.createDirectories(root.resolve(".ai-code-index"));
        Files.writeString(root.resolve("src/Zeta.ts"), "export const zeta = true;");
        Files.writeString(root.resolve("src/Alpha.ts"), "export const alpha = true;");
        Files.writeString(root.resolve("src/Beta.ts"), "export const beta = true;");
        Files.writeString(root.resolve("dist/Unsafe.ts"), "export const unsafe = true;");
        Files.writeString(root.resolve("target/Unsafe.ts"), "export const unsafe = true;");
        Files.writeString(root.resolve(".ai-code-index/Unsafe.ts"), "export const unsafe = true;");
        EditLocatorProperties properties = new EditLocatorProperties();
        properties.setMaxProjectIndexFiles(2);

        EditContextPackage contextPackage = builder(properties).build(
                workspace(root), List.of(candidate("src/Alpha.ts"))
        );

        assertEquals("\u9879\u76ee\u6587\u4ef6\u7d22\u5f15:\n- src/Alpha.ts\n- src/Beta.ts", contextPackage.projectIndex());
    }

    private EditContextPackageBuilder builder(EditLocatorProperties properties) {
        return new EditContextPackageBuilder(new EditWorkspaceFileService(properties), properties);
    }

    private EditFileCandidate candidate(String relativePath) {
        return new EditFileCandidate(relativePath, Path.of(relativePath).getFileName().toString(),
                "test", 1, "test", List.of());
    }

    private Path cleanTestRoot(String caseName) {
        Path root = Path.of("target", "test-workspaces", "edit-context-package", caseName)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(root.toFile());
        return root;
    }

    private GenerationWorkspace workspace(Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        return new GenerationWorkspace(
                1L,
                CodeGenTypeEnum.VUE_PROJECT,
                normalizedRoot,
                normalizedRoot,
                true,
                normalizedRoot,
                null,
                GenerationWorkspaceService.HIDDEN_FILE_NAMES,
                GenerationWorkspaceService.EDITABLE_EXTENSIONS
        );
    }
}
