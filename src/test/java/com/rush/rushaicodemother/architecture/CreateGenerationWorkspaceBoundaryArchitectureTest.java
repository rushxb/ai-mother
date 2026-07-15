package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps CREATE and full-stack allocation on the canonical generation-workspace boundary. */
class CreateGenerationWorkspaceBoundaryArchitectureTest {

    private static final Path PORT_ALLOCATOR_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "fullstack", "FullStackPortAllocator.java"
    );
    private static final Path CREATE_RUNTIME_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "create", "CreateTemplateRuntime.java"
    );
    private static final List<String> FORBIDDEN_PATH_IMPLEMENTATION = List.of(
            "CODE_OUTPUT_ROOT_DIR",
            "AppConstant",
            "Path.of(",
            "new File(",
            "File.separator",
            "full_stack_project_",
            "getValue() +"
    );

    @Test
    void fullStackPortAllocatorMustResolveCanonicalWorkspace() throws Exception {
        String source = Files.readString(PORT_ALLOCATOR_SOURCE);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("FullStackGenerationContext.create(frontendPort, backendPort, workspace)"));
        assertNoGeneratedPathRebuild(source, "FullStackPortAllocator");
    }

    @Test
    void createTemplateRuntimeMustResolveWorkspaceAndDelegateBootstrapByIdentity() throws Exception {
        String source = Files.readString(CREATE_RUNTIME_SOURCE);

        assertTrue(source.contains("GenerationWorkspaceService"));
        assertTrue(source.contains("generationWorkspaceService.resolve("));
        assertTrue(source.contains("workspace.canonicalRootPath()"));
        assertTrue(source.contains("vueProjectTemplateBootstrapService.bootstrapIfNecessary("));
        assertTrue(source.contains("backendProjectTemplateBootstrapService.bootstrapIfNecessary("));
        assertTrue(source.contains("fullStackPortAllocator.allocate(workspace)"));
        assertFalse(source.contains("workspace.frontendRootPath()"));
        assertFalse(source.contains("workspace.backendRootPath()"));
        assertNoGeneratedPathRebuild(source, "CreateTemplateRuntime");
    }

    private void assertNoGeneratedPathRebuild(String source, String component) {
        for (String forbidden : FORBIDDEN_PATH_IMPLEMENTATION) {
            assertFalse(source.contains(forbidden),
                    () -> component + " rebuilds a generated project path: " + forbidden);
        }
    }
}
