package com.rush.rushaicodemother.service.screenshot;

import com.rush.rushaicodemother.config.CodeDeploymentProperties;
import com.rush.rushaicodemother.config.ScreenshotProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.service.storage.ObjectStorageService;
import com.rush.rushaicodemother.service.storage.ObjectStorageUpload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultScreenshotServiceTest {

    private Path tempDirectory;
    private Path screenshotRoot;
    private WebPageScreenshotRenderer renderer;
    private ObjectStorageService objectStorageService;
    private DefaultScreenshotService screenshotService;

    @BeforeEach
    void setUp() throws IOException {
        tempDirectory = Path.of("target", "test-workspaces", "default-screenshot-service")
                .toAbsolutePath()
                .normalize();
        deleteRecursively(tempDirectory);
        Files.createDirectories(tempDirectory);
        screenshotRoot = tempDirectory.resolve("workspaces");
        renderer = mock(WebPageScreenshotRenderer.class);
        objectStorageService = mock(ObjectStorageService.class);

        ScreenshotProperties screenshotProperties = new ScreenshotProperties();
        screenshotProperties.setEnabled(true);
        screenshotProperties.setWorkDirectory(screenshotRoot);
        CodeDeploymentProperties deploymentProperties = new CodeDeploymentProperties();
        deploymentProperties.setDeployHost("http://localhost:91/static");
        screenshotService = new DefaultScreenshotService(
                renderer,
                objectStorageService,
                screenshotProperties,
                deploymentProperties,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC),
                () -> "fixed-id-1234"
        );
    }

    @AfterEach
    void cleanUp() throws IOException {
        deleteRecursively(tempDirectory);
    }

    @Test
    void shouldRenderUploadAndDeleteWholeWorkspace() throws IOException {
        URI target = URI.create("http://localhost:91/static/Deploy123/");
        when(renderer.render(eq(target), any(Path.class))).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(1);
            return Files.writeString(workspace.resolve("screenshot.jpg"), "image");
        });
        when(objectStorageService.upload(any(ObjectStorageUpload.class)))
                .thenReturn("https://cdn.example.com/cover.jpg");

        String publicUrl = screenshotService.generateAndUploadScreenshot(target.toString());

        assertEquals("https://cdn.example.com/cover.jpg", publicUrl);
        ArgumentCaptor<ObjectStorageUpload> uploadCaptor = ArgumentCaptor.forClass(ObjectStorageUpload.class);
        verify(objectStorageService).upload(uploadCaptor.capture());
        assertEquals(
                "screenshots/2026/07/14/fixed-id-1234.jpg",
                uploadCaptor.getValue().objectKey()
        );
        assertWorkspaceRootEmpty();
    }

    @Test
    void shouldRejectTargetsOutsideConfiguredDeploymentRoot() {
        assertThrows(
                BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot("http://169.254.169.254/latest/meta-data/")
        );
        assertThrows(
                BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot(
                        "http://localhost:91/static/%2e%2e/private/"
                )
        );

        verify(renderer, never()).render(any(URI.class), any(Path.class));
        verify(objectStorageService, never()).upload(any(ObjectStorageUpload.class));
    }

    @Test
    void shouldRejectRendererOutputOutsideOwnedWorkspaceWithoutDeletingIt() throws IOException {
        Path outsideFile = Files.writeString(tempDirectory.resolve("outside.jpg"), "outside");
        when(renderer.render(any(URI.class), any(Path.class))).thenReturn(outsideFile);

        assertThrows(
                BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot(
                        "http://localhost:91/static/Deploy123/"
                )
        );

        assertTrue(Files.isRegularFile(outsideFile));
        assertWorkspaceRootEmpty();
    }

    @Test
    void shouldDeleteWorkspaceWhenObjectStorageUploadFails() throws IOException {
        when(renderer.render(any(URI.class), any(Path.class))).thenAnswer(invocation -> {
            Path workspace = invocation.getArgument(1);
            return Files.writeString(workspace.resolve("screenshot.jpg"), "image");
        });
        when(objectStorageService.upload(any(ObjectStorageUpload.class)))
                .thenThrow(new BusinessException(50001, "upload failed"));

        assertThrows(
                BusinessException.class,
                () -> screenshotService.generateAndUploadScreenshot(
                        "http://localhost:91/static/Deploy123/"
                )
        );

        assertWorkspaceRootEmpty();
    }

    private void assertWorkspaceRootEmpty() throws IOException {
        assertTrue(Files.isDirectory(screenshotRoot));
        try (var children = Files.list(screenshotRoot)) {
            assertEquals(0, children.count());
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (root == null || Files.notExists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
