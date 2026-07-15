package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Keeps generated and deployed artifacts behind the injected code-storage boundary. */
class CodeStorageBoundaryArchitectureTest {

    private static final Path STATIC_CONTROLLER = sourcePath(
            "controller", "StaticResourceController.java"
    );
    private static final Path DEPLOYMENT_RESOURCE_SERVICE = sourcePath(
            "service", "artifact", "DeploymentArtifactResourceService.java"
    );
    private static final Path LOCAL_DEPLOYMENT_SERVICE = sourcePath(
            "service", "deployment", "LocalAppDeploymentService.java"
    );
    private static final Path ARTIFACT_LIFECYCLE_SERVICE = sourcePath(
            "service", "artifact", "LocalAppArtifactLifecycleService.java"
    );
    private static final Path GENERATION_WORKSPACE_SERVICE = sourcePath(
            "orchestration", "workspace", "GenerationWorkspaceService.java"
    );
    private static final List<String> FORBIDDEN_STATIC_STORAGE_ACCESS = List.of(
            "AppConstant",
            "CODE_OUTPUT_ROOT_DIR",
            "CODE_DEPLOY_ROOT_DIR"
    );

    @Test
    void staticControllerMustUseDeploymentResourceModule() throws Exception {
        String source = Files.readString(STATIC_CONTROLLER);

        assertTrue(source.contains("DeploymentArtifactResourceService"));
        assertTrue(source.contains("deploymentArtifactResourceService.resolve("));
        assertFalse(source.contains("SecurePathResolver"));
        assertFalse(source.contains("Path.of("));
        assertNoStaticStorageAccess(source, "StaticResourceController");
    }

    @Test
    void deploymentResourceModuleMustResolveFromInjectedDeployRoot() throws Exception {
        String source = Files.readString(DEPLOYMENT_RESOURCE_SERVICE);

        assertTrue(source.contains("CodeStorageProperties"));
        assertTrue(source.contains("storageProperties.deployRoot()"));
        assertTrue(source.contains("securePathResolver.resolveRegularFile("));
        assertTrue(source.contains("DeploymentKeyPolicy"));
        assertTrue(source.contains("deploymentKeyPolicy.requireValid("));
        assertFalse(source.contains("Pattern.compile("));
        assertFalse(source.contains("outputRoot()"));
        assertNoStaticStorageAccess(source, "DeploymentArtifactResourceService");
    }

    @Test
    void artifactLifecycleAndWorkspaceModulesMustUseInjectedStorageConfiguration() throws Exception {
        String lifecycleSource = Files.readString(ARTIFACT_LIFECYCLE_SERVICE);
        String workspaceSource = Files.readString(GENERATION_WORKSPACE_SERVICE);

        assertTrue(lifecycleSource.contains("CodeStorageProperties"));
        assertTrue(lifecycleSource.contains("storageProperties.outputRoot()"));
        assertTrue(lifecycleSource.contains("storageProperties.deployRoot()"));
        assertTrue(lifecycleSource.contains("DeploymentKeyPolicy"));
        assertFalse(lifecycleSource.contains("Pattern.compile("));
        assertTrue(workspaceSource.contains("CodeStorageProperties"));
        assertTrue(workspaceSource.contains("storageProperties.outputRoot()"));
        assertNoStaticStorageAccess(lifecycleSource, "LocalAppArtifactLifecycleService");
        assertNoStaticStorageAccess(workspaceSource, "GenerationWorkspaceService");
    }

    @Test
    void deploymentModulesMustShareOneDeploymentKeyPolicy() throws Exception {
        String source = Files.readString(LOCAL_DEPLOYMENT_SERVICE);

        assertTrue(source.contains("DeploymentKeyPolicy"));
        assertTrue(source.contains("deploymentKeyPolicy.isValid("));
        assertFalse(source.contains("Pattern.compile("));
    }

    private void assertNoStaticStorageAccess(String source, String moduleName) {
        for (String forbidden : FORBIDDEN_STATIC_STORAGE_ACCESS) {
            assertFalse(source.contains(forbidden),
                    () -> moduleName + " bypasses the injected storage boundary: " + forbidden);
        }
    }

    private static Path sourcePath(String... childSegments) {
        Path sourceRoot = Path.of(
                "src", "main", "java", "com", "rush", "rushaicodemother"
        );
        for (String segment : childSegments) {
            sourceRoot = sourceRoot.resolve(segment);
        }
        return sourceRoot;
    }
}
