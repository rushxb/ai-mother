package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents the generation facade from reconstructing or statically persisting workspace paths. */
class AiCodeGeneratorFacadeWorkspaceBoundaryArchitectureTest {

    private static final Path FACADE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "core",
            "AiCodeGeneratorFacade.java"
    );

    @Test
    void facadeMustUseInjectedSaverAndCanonicalWorkspaceService() throws Exception {
        String source = Files.readString(FACADE);

        assertTrue(source.contains("private final CodeFileSaverExecutor codeFileSaverExecutor"));
        assertTrue(source.contains("private final GenerationWorkspaceService generationWorkspaceService"));
        assertTrue(source.contains("codeFileSaverExecutor.executeSaver("));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("workspace.canonicalRootPath()"));
        assertFalse(source.contains("AppConstant"));
        assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
        assertFalse(source.contains("CodeFileSaverExecutor.executeSaver("));
        assertFalse(source.contains("codeGenType.getValue() + \"_\" + appId"));
    }
}
