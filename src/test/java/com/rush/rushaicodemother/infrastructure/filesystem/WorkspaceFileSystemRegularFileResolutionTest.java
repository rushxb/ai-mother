package com.rush.rushaicodemother.infrastructure.filesystem;

import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WorkspaceFileSystemRegularFileResolutionTest {

    @TempDir
    private Path tempDirectory;

    private final WorkspaceFileSystemService service =
            new WorkspaceFileSystemService(new WorkspaceFileSystemProperties());

    @Test
    void shouldResolveExistingRegularFileInsideWorkspace() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("workspace"));
        Path manifest = Files.writeString(workspace.resolve("package.json"), "{}");

        Path resolved = service.resolveExistingRegularFile(workspace, "package.json");

        assertEquals(manifest.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void shouldClassifyMissingDirectoryAndMissingFile() throws Exception {
        Path workspace = tempDirectory.resolve("missing-workspace");
        WorkspaceFileSystemException missingDirectory = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.resolveExistingRegularFile(workspace, "package.json")
        );

        Files.createDirectory(workspace);
        WorkspaceFileSystemException missingFile = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.resolveExistingRegularFile(workspace, "package.json")
        );

        assertEquals(WorkspaceFileSystemException.Reason.MISSING_DIRECTORY, missingDirectory.reason());
        assertEquals(WorkspaceFileSystemException.Reason.MISSING_FILE, missingFile.reason());
    }

    @Test
    void shouldRejectDirectoryAndTraversalInsteadOfTreatingThemAsFiles() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("workspace"));
        Files.createDirectory(workspace.resolve("package.json"));

        WorkspaceFileSystemException directory = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.resolveExistingRegularFile(workspace, "package.json")
        );
        WorkspaceFileSystemException traversal = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.resolveExistingRegularFile(workspace, "../outside.json")
        );

        assertEquals(WorkspaceFileSystemException.Reason.NOT_REGULAR_FILE, directory.reason());
        assertEquals(WorkspaceFileSystemException.Reason.INVALID_PATH, traversal.reason());
    }

    @Test
    void shouldRejectSymbolicManifestInsteadOfFollowingIt() throws Exception {
        Path workspace = Files.createDirectory(tempDirectory.resolve("workspace"));
        Path externalManifest = Files.writeString(tempDirectory.resolve("external-package.json"), "{}");
        Path manifestLink = workspace.resolve("package.json");
        createSymbolicLinkOrSkip(manifestLink, externalManifest);

        WorkspaceFileSystemException exception = assertThrows(
                WorkspaceFileSystemException.class,
                () -> service.resolveExistingRegularFile(workspace, "package.json")
        );

        assertEquals(WorkspaceFileSystemException.Reason.UNSAFE_SYMBOLIC_LINK, exception.reason());
    }

    private void createSymbolicLinkOrSkip(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            assumeTrue(false, "当前环境不支持创建符号链接: " + exception.getClass().getSimpleName());
        }
    }
}
