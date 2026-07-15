package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Prevents template consumers from bypassing canonical workspaces or bounded materialization. */
class TemplateWorkspaceBoundaryArchitectureTest {

    private static final Path TEMPLATE_PACKAGE = sourcePath("orchestration", "template");
    private static final Path MATERIALIZER = TEMPLATE_PACKAGE.resolve("ProjectTemplateMaterializer.java");
    private static final List<Path> BOOTSTRAP_SERVICES = List.of(
            TEMPLATE_PACKAGE.resolve("VueProjectTemplateBootstrapService.java"),
            TEMPLATE_PACKAGE.resolve("BackendProjectTemplateBootstrapService.java")
    );
    private static final List<Path> CALLERS = List.of(
            sourcePath("orchestration", "agent", "TemplateAgentNode.java"),
            sourcePath("orchestration", "create", "CreateTemplateRuntime.java")
    );

    @Test
    void materializerMustOwnBoundedNoFollowStagingAndAtomicPublication() throws Exception {
        String source = Files.readString(MATERIALIZER);

        assertTrue(source.contains("TemplateMaterializationProperties"));
        assertTrue(source.contains("maxFiles" ) || source.contains("getMaxFiles()"));
        assertTrue(source.contains("getMaxFileBytes()"));
        assertTrue(source.contains("getMaxTotalBytes()"));
        assertTrue(source.contains("CREATE_NEW"));
        assertTrue(source.contains("NOFOLLOW_LINKS"));
        assertTrue(source.contains("ATOMIC_MOVE"));
        assertTrue(source.contains("StagingCustomizer"));
        assertFalse(source.contains("StandardCopyOption.REPLACE_EXISTING"));
    }

    @Test
    void bootstrapServicesMustResolveCanonicalWorkspaceIdentityWithoutStaticRootsOrPathOverloads() throws Exception {
        for (Path service : BOOTSTRAP_SERVICES) {
            String source = Files.readString(service);
            String name = service.getFileName().toString();

            assertTrue(source.contains("GenerationWorkspaceService"), () -> name + " must use canonical workspaces");
            assertTrue(source.contains("ProjectTemplateBootstrapper"), () -> name + " must use atomic bootstrap");
            assertFalse(source.contains("AppConstant"), () -> name + " reads a static root");
            assertFalse(source.contains("CODE_OUTPUT_ROOT_DIR"), () -> name + " reads a static root");
            assertFalse(source.contains("Path codeOutputRoot"), () -> name + " retains a path constructor");
            assertFalse(source.contains("bootstrapIfNecessary(Path"), () -> name + " exposes an unsafe path API");
            assertFalse(source.contains("PathMatchingResourcePatternResolver"), () -> name + " duplicates resource copying");
        }
    }

    @Test
    void callersAndPreWarmRunnerMustNotReconstructOrDuplicateTemplatePaths() throws Exception {
        for (Path caller : CALLERS) {
            String source = Files.readString(caller);
            assertFalse(source.contains("Path.of(fullStackContext.workspaceRoot())"));
            assertFalse(source.contains("workspace.frontendRootPath(),"));
            assertFalse(source.contains("workspace.backendRootPath())"));
        }
        String runner = Files.readString(TEMPLATE_PACKAGE.resolve("TemplateNodeModulesPreWarmRunner.java"));
        String preWarmService = Files.readString(TEMPLATE_PACKAGE.resolve("TemplatePreWarmService.java"));

        assertTrue(runner.contains("ProjectTemplateMaterializer"));
        assertFalse(runner.contains("PathMatchingResourcePatternResolver"));
        assertFalse(runner.contains("copyTemplateToTemp"));
        assertFalse(preWarmService.contains("FileUtil"));
        assertFalse(preWarmService.contains("ConcurrentHashMap<String, ReentrantLock>"));
    }

    private static Path sourcePath(String... childSegments) {
        Path sourceRoot = Path.of("src", "main", "java", "com", "rush", "rushaicodemother");
        for (String segment : childSegments) {
            sourceRoot = sourceRoot.resolve(segment);
        }
        return sourceRoot;
    }
}