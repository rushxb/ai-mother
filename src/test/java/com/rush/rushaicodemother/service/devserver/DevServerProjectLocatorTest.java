package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.CodeStorageProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DevServerProjectLocatorTest {

    private Path outputRoot;

    @BeforeEach
    void setUp() throws Exception {
        outputRoot = DevServerTestWorkspace.create("project-locator");
    }

    @AfterEach
    void tearDown() throws Exception {
        DevServerTestWorkspace.delete(outputRoot);
    }

    @Test
    void shouldLocateVueProjectInsideOutputRoot() throws IOException {
        Path expected = createProject(outputRoot.resolve("vue_project_11"));

        Path actual = locator().locate(app(11L, CodeGenTypeEnum.VUE_PROJECT));

        assertEquals(expected.toRealPath(), actual);
    }

    @Test
    void shouldLocateFullStackFrontendDirectory() throws IOException {
        Path expected = createProject(outputRoot.resolve("full_stack_project_12/frontend"));

        Path actual = locator()
                .locate(app(12L, CodeGenTypeEnum.FULL_STACK_PROJECT));

        assertEquals(expected.toRealPath(), actual);
    }

    @Test
    void shouldRejectUnsupportedGenerationType() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> locator().locate(app(11L, CodeGenTypeEnum.HTML))
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
    }

    @Test
    void shouldRejectMissingProjectAndPackageManifest() throws IOException {
        DevServerProjectLocator locator = locator();

        BusinessException missingProject = assertThrows(
                BusinessException.class,
                () -> locator.locate(app(11L, CodeGenTypeEnum.VUE_PROJECT))
        );
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), missingProject.getCode());

        Files.createDirectories(outputRoot.resolve("vue_project_11"));
        BusinessException missingManifest = assertThrows(
                BusinessException.class,
                () -> locator.locate(app(11L, CodeGenTypeEnum.VUE_PROJECT))
        );
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), missingManifest.getCode());
    }

    @Test
    void shouldRejectSymbolicProjectDirectory() throws IOException {
        Path externalProject = createProject(outputRoot.resolve("external"));
        Path link = outputRoot.resolve("vue_project_11");
        boolean linked = createSymbolicLink(link, externalProject);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> locator().locate(app(11L, CodeGenTypeEnum.VUE_PROJECT))
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    @Test
    void shouldRejectSymbolicPackageManifest() throws IOException {
        Path projectDirectory = Files.createDirectories(outputRoot.resolve("vue_project_11"));
        Path externalManifest = Files.writeString(outputRoot.resolve("external-package.json"), "{}");
        boolean linked = createSymbolicLink(projectDirectory.resolve("package.json"), externalManifest);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> locator().locate(app(11L, CodeGenTypeEnum.VUE_PROJECT))
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
    }

    private DevServerProjectLocator locator() {
        CodeStorageProperties storageProperties = new CodeStorageProperties();
        storageProperties.setOutputRootDir(outputRoot);
        return new DevServerProjectLocator(
                new GenerationWorkspaceService(storageProperties),
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties())
        );
    }

    private App app(Long appId, CodeGenTypeEnum type) {
        return App.builder().id(appId).codeGenType(type.getValue()).build();
    }

    private Path createProject(Path projectDirectory) throws IOException {
        Files.createDirectories(projectDirectory);
        Files.writeString(projectDirectory.resolve("package.json"), "{}");
        return projectDirectory;
    }

    private boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }
}
