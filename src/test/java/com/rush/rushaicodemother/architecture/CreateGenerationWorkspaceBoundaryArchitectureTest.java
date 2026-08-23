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
    private static final Path TEMPLATE_BOOTSTRAP_REGISTRY_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "template", "bootstrap", "GenerationTemplateBootstrapRegistry.java"
    );
    private static final Path TEMPLATE_BOOTSTRAP_RESULT_SOURCE = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother",
            "orchestration", "template", "bootstrap", "GenerationTemplateBootstrapResult.java"
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
        assertTrue(source.contains("return allocate(workspace);"));
        assertNoGeneratedPathRebuild(source, "FullStackPortAllocator");
    }

    @Test
    void templateBootstrapRegistryMustOwnWorkspaceResolutionForCreateRuntime() throws Exception {
        String runtimeSource = Files.readString(CREATE_RUNTIME_SOURCE);
        String registrySource = Files.readString(TEMPLATE_BOOTSTRAP_REGISTRY_SOURCE);
        String resultSource = Files.readString(TEMPLATE_BOOTSTRAP_RESULT_SOURCE);

        assertTrue(registrySource.contains("GenerationWorkspaceService"));
        assertTrue(registrySource.contains("workspaceService.resolve(appId, codeGenType)"));
        assertTrue(registrySource.contains("completed(\n                codeGenType, workspace, output)"));
        assertTrue(resultSource.contains("workspace.canonicalRootPath()"));
        assertTrue(runtimeSource.contains("GenerationTemplateBootstrapRegistry"));
        assertTrue(runtimeSource.contains("templateBootstrapRegistry.bootstrap("));
        assertFalse(runtimeSource.contains("GenerationWorkspaceService"));
        assertFalse(runtimeSource.contains("ProjectTemplateBootstrapService"));
        assertFalse(runtimeSource.contains("FullStackPortAllocator"));
        assertNoGeneratedPathRebuild(registrySource, "GenerationTemplateBootstrapRegistry");
        assertNoGeneratedPathRebuild(resultSource, "GenerationTemplateBootstrapResult");
        assertNoGeneratedPathRebuild(runtimeSource, "CreateTemplateRuntime");
    }

    private void assertNoGeneratedPathRebuild(String source, String component) {
        for (String forbidden : FORBIDDEN_PATH_IMPLEMENTATION) {
            assertFalse(source.contains(forbidden),
                    () -> component + " rebuilds a generated project path: " + forbidden);
        }
    }
}
