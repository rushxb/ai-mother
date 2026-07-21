package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWorkspaceServiceTest {

    @TempDir
    Path tempDirectory;

    private final GenerationWorkspaceService service = new GenerationWorkspaceService(new CodeStorageProperties());

    @Test
    void shouldResolveVueWorkspaceUnderCodeOutputRoot() {
        App app = new App();
        app.setId(123L);

        GenerationWorkspace workspace = service.resolve(app, CodeGenTypeEnum.VUE_PROJECT);

        assertEquals(123L, workspace.appId());
        assertEquals(CodeGenTypeEnum.VUE_PROJECT, workspace.codeGenType());
        assertTrue(workspace.canonicalRootPath().endsWith(Path.of("vue_project_123")));
        assertTrue(workspace.canonicalRootPath().startsWith(new CodeStorageProperties().outputRoot()));
        assertEquals(workspace.canonicalRootPath(), workspace.frontendRootPath());
        assertNull(workspace.backendRootPath());
        assertFalse(workspace.exists());
        assertTrue(workspace.hiddenFileNames().contains("node_modules"));
        assertTrue(workspace.editableExtensions().contains("vue"));
    }

    @Test
    void shouldResolveFullStackSubDirectories() {
        App app = new App();
        app.setId(456L);

        GenerationWorkspace workspace = service.resolve(app, CodeGenTypeEnum.FULL_STACK_PROJECT);

        assertTrue(workspace.canonicalRootPath().endsWith(Path.of("full_stack_project_456")));
        assertNotNull(workspace.frontendRootPath());
        assertNotNull(workspace.backendRootPath());
        assertTrue(workspace.frontendRootPath().endsWith(Path.of("full_stack_project_456", "frontend")));
        assertTrue(workspace.backendRootPath().endsWith(Path.of("full_stack_project_456", "backend")));
    }

    @Test
    void shouldResolveWorkspaceByTaskIdentityWithoutPersistenceEntity() {
        GenerationWorkspace workspace = service.resolve(789L, CodeGenTypeEnum.FULL_STACK_PROJECT);

        assertEquals(789L, workspace.appId());
        assertTrue(workspace.canonicalRootPath().isAbsolute());
        assertEquals(
                workspace.canonicalRootPath().resolve("frontend").normalize(),
                workspace.frontendRootPath()
        );
        assertEquals(
                workspace.canonicalRootPath().resolve("backend").normalize(),
                workspace.backendRootPath()
        );
    }

    @Test
    void shouldResolveExactArtifactReportedWorkspace() throws Exception {
        Path outputRoot = tempDirectory.resolve("code_output");
        Path projectRoot = Files.createDirectories(outputRoot.resolve("vue_project_901"));
        GenerationWorkspaceService boundedService = service(outputRoot);

        GenerationWorkspace workspace = boundedService.resolveReportedWorkspace(901L, projectRoot);

        assertEquals(projectRoot.toAbsolutePath().normalize(), workspace.canonicalRootPath());
        assertTrue(workspace.exists());
    }

    @Test
    void shouldRejectArtifactReportedWorkspaceFromAnotherApplication() throws Exception {
        Path outputRoot = tempDirectory.resolve("code_output");
        Path anotherProjectRoot = Files.createDirectories(outputRoot.resolve("vue_project_902"));
        GenerationWorkspaceService boundedService = service(outputRoot);

        ReportedWorkspaceResolutionException exception = assertThrows(
                ReportedWorkspaceResolutionException.class,
                () -> boundedService.resolveReportedWorkspace(901L, anotherProjectRoot)
        );

        assertEquals(ReportedWorkspaceResolutionException.Reason.CONTEXT_MISMATCH, exception.reason());
    }

    @Test
    void shouldClassifyNonDirectoryCanonicalWorkspaceAsUnsafe() throws Exception {
        Path outputRoot = Files.createDirectories(tempDirectory.resolve("code_output"));
        Path unsafeWorkspace = Files.writeString(outputRoot.resolve("vue_project_903"), "not a directory");
        GenerationWorkspaceService boundedService = service(outputRoot);

        ReportedWorkspaceResolutionException exception = assertThrows(
                ReportedWorkspaceResolutionException.class,
                () -> boundedService.resolveReportedWorkspace(903L, unsafeWorkspace)
        );

        assertEquals(ReportedWorkspaceResolutionException.Reason.UNSAFE_WORKSPACE, exception.reason());
    }

    @Test
    void shouldRejectInvalidTaskIdentityBeforeResolvingFilesystemPath() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.resolve(0L, CodeGenTypeEnum.VUE_PROJECT)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
    }

    @Test
    void shouldResolveOnlyThePublishedWorkspaceOwnedByTheExpectedTask() throws Exception {
        Path outputRoot = tempDirectory.resolve("published-output");
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(outputRoot);
        properties.setDeployRootDir(tempDirectory.resolve("published-deploy"));
        properties.setSnapshotRootDir(tempDirectory.resolve("published-snapshot"));
        GenerationWorkspacePublicationCatalog catalog =
                new GenerationWorkspacePublicationCatalog(properties);
        GenerationWorkspaceService publishedService = new GenerationWorkspaceService(
                properties, new GenerationWorkspaceExecutionScope(), catalog);
        GenerationWorkspacePublicationPointer pointer = new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                904L,
                CodeGenTypeEnum.VUE_PROJECT,
                "task-published",
                7L,
                Instant.parse("2026-07-20T10:00:00Z")
        );
        Path publishedRoot = catalog.prepareVersionParent(pointer).resolve("workspace");
        Files.createDirectory(publishedRoot);
        Files.writeString(publishedRoot.resolve("package.json"), "{}");
        catalog.writeOwnerMarker(publishedRoot, pointer);
        catalog.activate(pointer);

        GenerationWorkspace workspace = publishedService.resolvePublished(
                904L, CodeGenTypeEnum.VUE_PROJECT, "task-published");

        assertEquals(publishedRoot.toRealPath(), workspace.canonicalRootPath());
        assertThrows(BusinessException.class, () -> publishedService.resolvePublished(
                904L, CodeGenTypeEnum.VUE_PROJECT, "task-stale"));
    }

    private GenerationWorkspaceService service(Path outputRoot) {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(outputRoot);
        properties.setDeployRootDir(tempDirectory.resolve("code_deploy"));
        properties.setSnapshotRootDir(tempDirectory.resolve("code_snapshot"));
        return new GenerationWorkspaceService(properties);
    }

}
