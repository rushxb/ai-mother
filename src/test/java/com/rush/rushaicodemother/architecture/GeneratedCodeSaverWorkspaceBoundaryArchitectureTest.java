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
    void saverTemplateMustUseCanonicalWorkspaceAndAtomicFileSystemBoundaries() throws Exception {
        String source = Files.readString(SAVER_ROOT.resolve("CodeFileSaverTemplate.java"));

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.prepare("));
        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("writeUtf8Atomically("));
        assertTrue(source.contains("deleteFileIfExists("));
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
        for (String fileName : List.of(
                "HtmlCodeFileSaverTemplate.java",
                "MultiFileCodeFileSaverTemplate.java"
        )) {
            String source = Files.readString(SAVER_ROOT.resolve(fileName));
            assertTrue(source.contains("@Component"));
            assertFalse(source.contains("FileUtil"));
            assertFalse(source.contains("Files."));
            assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
        }
    }
}
