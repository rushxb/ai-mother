package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps Heavy Generation filesystem decisions behind the generation-workspace module. */
class HeavyGenerationWorkspaceBoundaryArchitectureTest {

    private static final Path HEAVY_GENERATION_PACKAGE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "orchestration", "heavy"
    );

    private static final List<String> WORKSPACE_RESOLVING_SERVICES = List.of(
            "HeavyGenerationExecutionService.java",
            "HeavyGenerationBuildValidationService.java",
            "HeavyGenerationFinalizationService.java"
    );

    private static final List<String> FORBIDDEN_PATH_IMPLEMENTATION = List.of(
            "CODE_OUTPUT_ROOT_DIR",
            "File.separator",
            "new File("
    );

    @Test
    void heavyGenerationMustResolveGeneratedProjectsThroughWorkspaceService() throws IOException {
        for (String sourceFileName : WORKSPACE_RESOLVING_SERVICES) {
            Path sourcePath = HEAVY_GENERATION_PACKAGE.resolve(sourceFileName);
            String source = Files.readString(sourcePath);

            assertTrue(
                    source.contains("GenerationWorkspaceService"),
                    () -> sourcePath + " must depend on the generation-workspace module"
            );
            assertTrue(
                    source.contains("generationWorkspaceService.resolve("),
                    () -> sourcePath + " must resolve project paths through GenerationWorkspaceService"
            );
        }
    }

    @Test
    void heavyGenerationPackageMustNotRebuildGeneratedProjectPaths() throws IOException {
        try (var sources = Files.list(HEAVY_GENERATION_PACKAGE)) {
            for (Path sourcePath : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(sourcePath);
                for (String forbidden : FORBIDDEN_PATH_IMPLEMENTATION) {
                    assertFalse(
                            source.contains(forbidden),
                            () -> sourcePath + " bypasses generation-workspace module: " + forbidden
                    );
                }
            }
        }
    }
}
