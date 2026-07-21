package com.rush.rushaicodemother.orchestration.workspace;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWorkspacePublicationCatalogTest {

    private static final Instant NOW = Instant.parse("2026-07-20T06:00:00Z");

    @TempDir
    Path tempDirectory;

    @Test
    void currentWorkspaceMustResolveOnlyWhenPointerAndOwnerMarkerMatch() throws Exception {
        GenerationWorkspacePublicationCatalog catalog = catalog();
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 3L);
        Path workspace = prepareWorkspace(catalog, pointer);
        Files.writeString(workspace.resolve("index.html"), "ok");

        catalog.writeOwnerMarker(workspace, pointer);
        catalog.activate(pointer);

        assertEquals(pointer, catalog.findCurrent(11L, CodeGenTypeEnum.VUE_PROJECT).orElseThrow());
        assertEquals(workspace.toRealPath(), catalog.findCurrentWorkspace(
                11L, CodeGenTypeEnum.VUE_PROJECT).orElseThrow());
    }

    @Test
    void resolveMustRejectWorkspaceOwnedByAnotherExecutionFence() throws Exception {
        GenerationWorkspacePublicationCatalog catalog = catalog();
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 3L);
        Path workspace = prepareWorkspace(catalog, pointer);
        catalog.writeOwnerMarker(workspace, pointer("task-2", 4L));
        catalog.activate(pointer);

        assertThrows(BusinessException.class, () -> catalog.findCurrentWorkspace(
                11L, CodeGenTypeEnum.VUE_PROJECT));
    }

    @Test
    void pointerReadMustRejectOversizedCatalogFiles() throws Exception {
        GenerationWorkspacePublicationCatalog catalog = catalog();
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 3L);
        catalog.activate(pointer);
        Path pointerPath = pointerPath();
        Files.writeString(pointerPath, "x".repeat(
                GenerationWorkspacePublicationCatalog.MAX_MANIFEST_BYTES + 1));

        assertThrows(BusinessException.class,
                () -> catalog.findCurrent(11L, CodeGenTypeEnum.VUE_PROJECT));
    }

    @Test
    void pointerReadMustRejectUnknownOrDuplicateFields() throws Exception {
        GenerationWorkspacePublicationCatalog catalog = catalog();
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 3L);
        catalog.activate(pointer);
        Path pointerPath = pointerPath();
        Files.writeString(pointerPath, Files.readString(pointerPath) + "unexpected=value\n");

        assertThrows(BusinessException.class,
                () -> catalog.findCurrent(11L, CodeGenTypeEnum.VUE_PROJECT));

        catalog.activate(pointer);
        Files.writeString(pointerPath, Files.readString(pointerPath) + "taskId=task-1\n");
        assertThrows(BusinessException.class,
                () -> catalog.findCurrent(11L, CodeGenTypeEnum.VUE_PROJECT));
    }

    @Test
    void ownerMarkerReadMustBeBounded() throws Exception {
        GenerationWorkspacePublicationCatalog catalog = catalog();
        GenerationWorkspacePublicationPointer pointer = pointer("task-1", 3L);
        Path workspace = prepareWorkspace(catalog, pointer);
        catalog.writeOwnerMarker(workspace, pointer);
        catalog.activate(pointer);
        Files.writeString(
                workspace.resolve(GenerationWorkspacePublicationCatalog.OWNER_MARKER_NAME),
                "x".repeat(GenerationWorkspacePublicationCatalog.MAX_MANIFEST_BYTES + 1));

        BusinessException failure = assertThrows(BusinessException.class,
                () -> catalog.findCurrentWorkspace(11L, CodeGenTypeEnum.VUE_PROJECT));
        assertTrue(failure.getMessage().contains("marker"));
    }

    private GenerationWorkspacePublicationCatalog catalog() {
        CodeStorageProperties properties = new CodeStorageProperties();
        properties.setOutputRootDir(tempDirectory.resolve("code-output"));
        properties.setDeployRootDir(tempDirectory.resolve("code-deploy"));
        properties.setSnapshotRootDir(tempDirectory.resolve("code-snapshot"));
        return new GenerationWorkspacePublicationCatalog(properties);
    }

    private GenerationWorkspacePublicationPointer pointer(String taskId, long epoch) {
        return new GenerationWorkspacePublicationPointer(
                GenerationWorkspacePublicationPointer.CURRENT_SCHEMA_VERSION,
                11L,
                CodeGenTypeEnum.VUE_PROJECT,
                taskId,
                epoch,
                NOW
        );
    }

    private Path prepareWorkspace(GenerationWorkspacePublicationCatalog catalog,
                                  GenerationWorkspacePublicationPointer pointer) throws Exception {
        Path workspace = catalog.prepareVersionParent(pointer).resolve("workspace");
        return Files.createDirectory(workspace).toRealPath();
    }

    private Path pointerPath() {
        return tempDirectory.resolve("code-output")
                .resolve(GenerationWorkspacePublicationCatalog.PUBLICATION_ROOT_NAME)
                .resolve("app-11")
                .resolve(CodeGenTypeEnum.VUE_PROJECT.getValue() + ".current");
    }
}
