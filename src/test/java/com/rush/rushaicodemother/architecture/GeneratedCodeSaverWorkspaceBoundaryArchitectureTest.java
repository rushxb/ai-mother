package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps generated-code persistence behind canonical workspace and bounded file-system services. */
class GeneratedCodeSaverWorkspaceBoundaryArchitectureTest {

    private static final Path SAVER_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "core", "saver"
    );

    @Test
    void saverTemplateMustValidateAllGeneratedFilesBeforePreparingWorkspace() throws Exception {
        String source = Files.readString(SAVER_ROOT.resolve("CodeFileSaverTemplate.java"));

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.prepare("));
        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("GeneratedWorkspaceTrustPolicy"));
        assertTrue(source.contains("generatedWorkspaceTrustPolicy.validate("));
        assertTrue(source.contains("generatedWorkspaceTrustPolicy.validateDeletion("));
        assertTrue(source.contains("protected abstract List<GeneratedCodeFile> generatedFiles"));
        assertTrue(source.contains("writeUtf8Atomically("));
        assertTrue(source.contains("deleteFileIfExists("));
        assertTrue(source.contains("requireTrustedFiles(typedResult)"));
        assertTrue(source.indexOf("requireTrustedFiles(typedResult)")
                < source.indexOf("generationWorkspaceService.prepare("));
        assertFalse(source.contains("protected abstract void saveFiles"));
        assertFalse(source.contains("AppConstant"));
        assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
        assertFalse(source.contains("FileUtil"));
        assertFalse(source.contains("Files.write"));
        assertFalse(source.contains("Files.createDirectories"));
    }

    @Test
    void saverExecutorMustRemainDependencyInjectedAndFreeOfStaticSingletonSavers() throws Exception {
        String source = Files.readString(SAVER_ROOT.resolve("CodeFileSaverExecutor.java"));

        assertTrue(source.contains("@Service"));
        assertTrue(source.contains("List<CodeFileSaverTemplate<?>> savers"));
        assertFalse(source.contains("static final HtmlCodeFileSaverTemplate"));
        assertFalse(source.contains("static final MultiFileCodeFileSaverTemplate"));
        assertFalse(source.contains("new HtmlCodeFileSaverTemplate"));
        assertFalse(source.contains("new MultiFileCodeFileSaverTemplate"));
    }

    @Test
    void concreteSaversMustNotPerformDirectFileSystemMutation() throws Exception {
        try (var saverFiles = Files.list(SAVER_ROOT)) {
            List<Path> concreteSavers = saverFiles
                    .filter(path -> path.getFileName().toString().endsWith("CodeFileSaverTemplate.java"))
                    .filter(path -> !path.getFileName().toString().equals("CodeFileSaverTemplate.java"))
                    .toList();
            assertFalse(concreteSavers.isEmpty());
            for (Path saverFile : concreteSavers) {
                String source = Files.readString(saverFile);
                assertTrue(source.contains("@Component"));
                assertTrue(source.contains("generatedFiles("));
                assertTrue(source.contains("new GeneratedCodeFile("));
                assertFalse(source.contains("synchronizeFile("));
                assertFalse(source.contains("writeUtf8Atomically("));
                assertFalse(source.contains("deleteFileIfExists("));
                assertFalse(source.contains("FileUtil"));
                assertFalse(source.contains("Files."));
                assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
            }
        }
    }
}
