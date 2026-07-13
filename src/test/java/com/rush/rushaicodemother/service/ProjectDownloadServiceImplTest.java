package com.rush.rushaicodemother.service;

import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectDownloadServiceImplTest {

    private final ProjectDownloadServiceImpl service = new ProjectDownloadServiceImpl();
    private Path workspace;

    @BeforeEach
    void setUp() throws IOException {
        workspace = Path.of("target", "test-workspaces", "project-download", UUID.randomUUID().toString())
                .toAbsolutePath();
        Files.createDirectories(workspace);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (!Files.exists(workspace)) {
            return;
        }
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void mustPackageOnlyAllowedProjectFilesAndSetSecurityHeaders() throws IOException {
        Path projectRoot = Files.createDirectory(workspace.resolve("project"));
        Files.createDirectories(projectRoot.resolve("src"));
        Files.writeString(projectRoot.resolve("src/main.txt"), "content", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("debug.log"), "ignored", StandardCharsets.UTF_8);
        Files.createDirectories(projectRoot.resolve("node_modules/package"));
        Files.writeString(projectRoot.resolve("node_modules/package/index.js"), "ignored", StandardCharsets.UTF_8);

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.downloadProjectAsZip(projectRoot.toString(), "project-42", response);

        assertEquals("application/zip", response.getContentType());
        assertEquals("attachment; filename=\"project-42.zip\"",
                response.getHeader("Content-Disposition"));
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        Set<String> entries = readEntryNames(response.getContentAsByteArray());
        assertTrue(entries.contains("src/main.txt"));
        assertFalse(entries.contains("debug.log"));
        assertFalse(entries.stream().anyMatch(name -> name.startsWith("node_modules/")));
    }

    @Test
    void mustRejectUnsafeDownloadFileNameBeforeWritingResponse() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(BusinessException.class, () -> service.downloadProjectAsZip(
                workspace.toString(), "bad\r\nInjected-Header", response));

        assertEquals(0, response.getContentAsByteArray().length);
    }

    @Test
    void mustNotIncludeSymbolicLinksWhenPlatformAllowsCreatingThem() throws IOException {
        Path projectRoot = Files.createDirectory(workspace.resolve("project"));
        Path outsideFile = Files.writeString(
                workspace.resolve("outside-secret.txt"), "secret", StandardCharsets.UTF_8);
        Path link = projectRoot.resolve("linked-secret.txt");
        try {
            Files.createSymbolicLink(link, outsideFile);
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            return;
        }

        MockHttpServletResponse response = new MockHttpServletResponse();
        service.downloadProjectAsZip(projectRoot.toString(), "project", response);

        Set<String> entries = readEntryNames(response.getContentAsByteArray());
        assertFalse(entries.contains("linked-secret.txt"));
    }

    private Set<String> readEntryNames(byte[] zipBytes) throws IOException {
        Set<String> entries = new HashSet<>();
        try (ZipInputStream zipInputStream = new ZipInputStream(
                new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zipInputStream.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }
        return entries;
    }
}