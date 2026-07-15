package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps runtime workspace consumers behind canonical workspace and file-system boundaries. */
class RuntimeWorkspaceConsumerArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    );

    @Test
    void devServerLocatorMustUseCanonicalWorkspaceAndBoundedFileSystemServices() throws Exception {
        String source = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "service", "devserver", "DevServerProjectLocator.java"
        )));

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("WorkspaceFileSystemService"));
        assertTrue(source.contains("resolveExistingRegularFile("));
        assertTrue(source.contains("workspace.frontendRootPath()"));
        assertFalse(source.contains("AppConstant"));
        assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"));
        assertFalse(source.contains("Files."));
        assertFalse(source.contains("codeGenType.getValue() + \"_\""));
    }

    @Test
    void generationAgentAssemblyAndSupportMustUseCanonicalWorkspaceService() throws Exception {
        String configuration = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "orchestration", "agent", "GenerationAgentConfiguration.java"
        )));
        String support = Files.readString(SOURCE_ROOT.resolve(Path.of(
                "orchestration", "agent", "GenerationAgentSupport.java"
        )));

        assertTrue(configuration.contains("GenerationWorkspaceService generationWorkspaceService"));
        assertTrue(support.contains("private final GenerationWorkspaceService generationWorkspaceService"));
        assertTrue(support.contains("generationWorkspaceService.resolve("));
        assertTrue(support.contains("workspace.canonicalRootPath()"));
        assertFalse(configuration.contains("AppConstant"));
        assertFalse(configuration.contains("CODE_OUTPUT_ROOT_DIR"));
        assertFalse(support.contains("codeOutputRoot"));
        assertFalse(support.contains("resolvedType.getValue() + \"_\""));
    }
}
