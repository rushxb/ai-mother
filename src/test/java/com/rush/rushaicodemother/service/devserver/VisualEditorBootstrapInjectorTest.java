package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class VisualEditorBootstrapInjectorTest {

    private Path tempDirectory;

    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = DevServerTestWorkspace.create("bootstrap-injector");
    }

    @AfterEach
    void tearDown() throws Exception {
        DevServerTestWorkspace.delete(tempDirectory);
    }

    private final VisualEditorBootstrapInjector injector = new VisualEditorBootstrapInjector();

    @Test
    void shouldInjectUtf8BootstrapExactlyOnce() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path indexHtml = projectDirectory.resolve("index.html");
        Files.writeString(indexHtml,
                "<html><head><title>\u4E2D\u6587\u6807\u9898</title></head><body>\u5185\u5BB9</body></html>",
                StandardCharsets.UTF_8);

        injector.inject(projectDirectory);
        String firstContent = Files.readString(indexHtml, StandardCharsets.UTF_8);
        injector.inject(projectDirectory);
        String secondContent = Files.readString(indexHtml, StandardCharsets.UTF_8);

        assertEquals(firstContent, secondContent);
        assertTrue(firstContent.contains("\u4E2D\u6587\u6807\u9898"));
        assertTrue(firstContent.contains("event.source !== window.parent"));
        assertTrue(firstContent.contains("channelId"));
        assertTrue(firstContent.contains("300000"));
        assertEquals(1, occurrences(firstContent, "id=\"visual-editor-bootstrap\""));
    }

    @Test
    void shouldLeaveFileUntouchedWhenHeadClosingTagIsMissing() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path indexHtml = projectDirectory.resolve("index.html");
        String original = "<html><body>no head closing tag</body></html>";
        Files.writeString(indexHtml, original, StandardCharsets.UTF_8);

        injector.inject(projectDirectory);

        assertEquals(original, Files.readString(indexHtml, StandardCharsets.UTF_8));
    }

    @Test
    void shouldRejectSymbolicIndexFile() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path externalIndex = Files.writeString(tempDirectory.resolve("outside.html"),
                "<html><head></head><body></body></html>", StandardCharsets.UTF_8);
        boolean linked = createSymbolicLink(projectDirectory.resolve("index.html"), externalIndex);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        injector.inject(projectDirectory);

        String externalContent = Files.readString(externalIndex, StandardCharsets.UTF_8);
        assertFalse(externalContent.contains("visual-editor-bootstrap"));
    }

    @Test
    void shouldPreservePosixPermissionsWhenSupported() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path indexHtml = projectDirectory.resolve("index.html");
        Files.writeString(indexHtml, "<html><head></head><body></body></html>", StandardCharsets.UTF_8);
        boolean posixSupported = Files.getFileStore(indexHtml).supportsFileAttributeView("posix");
        assumeTrue(posixSupported, "The file system does not support POSIX permissions");
        var permissions = java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----");
        Files.setPosixFilePermissions(indexHtml, permissions);

        injector.inject(projectDirectory);

        assertEquals(permissions, Files.getPosixFilePermissions(indexHtml));
    }

    private int occurrences(String source, String token) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(token, index)) >= 0) {
            count++;
            index += token.length();
        }
        return count;
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
