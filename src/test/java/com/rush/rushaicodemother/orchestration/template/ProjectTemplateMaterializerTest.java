package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.TemplateMaterializationProperties;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectTemplateMaterializerTest {

    @Test
    void shouldMaterializeRealTemplateAndPublishOnlyCompleteTarget() throws Exception {
        Path root = resetRoot("real-template");
        ProjectTemplateMaterializer materializer = materializer(
                new TemplateMaterializationProperties(),
                new PathMatchingResourcePatternResolver()
        );
        Path target = root.resolve("workspace");

        ProjectTemplateMaterializer.MaterializationResult result = materializer.materializeAtomically(
                ProjectTemplateCatalog.VUE_BASIC,
                target,
                staging -> Files.writeString(staging.resolve("customized.txt"), "ready")
        );

        assertTrue(result.fileCount() > 0);
        assertTrue(Files.isRegularFile(target.resolve("package.json")));
        assertEquals("ready", Files.readString(target.resolve("customized.txt")));
        try (var entries = Files.newDirectoryStream(root, ".workspace.template-*")) {
            assertFalse(entries.iterator().hasNext());
        }
    }

    @Test
    void shouldRejectTraversalAndLeaveNoTarget() throws Exception {
        Path root = resetRoot("traversal");
        Resource traversal = resource(ProjectTemplateCatalog.VUE_BASIC, "../escape.txt", "escape");
        ProjectTemplateMaterializer materializer = materializer(
                new TemplateMaterializationProperties(),
                resolver(traversal)
        );
        Path target = root.resolve("workspace");

        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> materializer.materializeAtomically(ProjectTemplateCatalog.VUE_BASIC, target, null)
        );

        assertEquals(TemplateMaterializationException.Reason.INVALID_RESOURCE_PATH, exception.reason());
        assertFalse(Files.exists(target));
        assertFalse(Files.exists(root.resolve("escape.txt")));
    }

    @Test
    void shouldRejectDuplicateResourcePaths() throws Exception {
        Path root = resetRoot("duplicate");
        Resource first = resource(ProjectTemplateCatalog.VUE_BASIC, "src/app.js", "one");
        Resource second = resource(ProjectTemplateCatalog.VUE_BASIC, "src/app.js", "two");
        ProjectTemplateMaterializer materializer = materializer(
                new TemplateMaterializationProperties(),
                resolver(first, second)
        );

        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> materializer.materializeAtomically(
                        ProjectTemplateCatalog.VUE_BASIC,
                        root.resolve("workspace"),
                        null
                )
        );

        assertEquals(TemplateMaterializationException.Reason.DUPLICATE_RESOURCE, exception.reason());
        assertFalse(Files.exists(root.resolve("workspace")));
    }

    @Test
    void shouldEnforceFileCountSingleFileAndTotalByteLimits() throws Exception {
        Path root = resetRoot("limits");

        TemplateMaterializationProperties fileCount = new TemplateMaterializationProperties();
        fileCount.setMaxFiles(1);
        assertReason(
                TemplateMaterializationException.Reason.FILE_LIMIT_EXCEEDED,
                materializer(fileCount, resolver(
                        resource(ProjectTemplateCatalog.VUE_BASIC, "one.txt", "1"),
                        resource(ProjectTemplateCatalog.VUE_BASIC, "two.txt", "2")
                )),
                root.resolve("file-count")
        );

        TemplateMaterializationProperties singleFile = new TemplateMaterializationProperties();
        singleFile.setMaxFileBytes(4);
        singleFile.setMaxTotalBytes(100);
        assertReason(
                TemplateMaterializationException.Reason.FILE_TOO_LARGE,
                materializer(singleFile, resolver(resource(ProjectTemplateCatalog.VUE_BASIC, "large.txt", "12345"))),
                root.resolve("single-file")
        );

        TemplateMaterializationProperties total = new TemplateMaterializationProperties();
        total.setMaxFileBytes(10);
        total.setMaxTotalBytes(5);
        assertReason(
                TemplateMaterializationException.Reason.TOTAL_BYTES_EXCEEDED,
                materializer(total, resolver(
                        resource(ProjectTemplateCatalog.VUE_BASIC, "one.txt", "123"),
                        resource(ProjectTemplateCatalog.VUE_BASIC, "two.txt", "456")
                )),
                root.resolve("total")
        );
    }

    @Test
    void shouldRejectTemplateOutsidePackagedCatalog() throws Exception {
        Path root = resetRoot("unknown-template");
        ProjectTemplateMaterializer materializer = materializer(
                new TemplateMaterializationProperties(),
                new PathMatchingResourcePatternResolver()
        );

        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> materializer.materializeAtomically("arbitrary-template", root.resolve("workspace"), null)
        );

        assertEquals(TemplateMaterializationException.Reason.INVALID_TEMPLATE, exception.reason());
        assertFalse(Files.exists(root.resolve("workspace")));
    }
    @Test
    void shouldRejectOrdinaryFileAtFinalTarget() throws Exception {
        Path root = resetRoot("unsafe-target");
        Files.createDirectories(root);
        Path target = Files.writeString(root.resolve("workspace"), "not a directory");
        ProjectTemplateMaterializer materializer = materializer(
                new TemplateMaterializationProperties(),
                resolver(resource(ProjectTemplateCatalog.VUE_BASIC, "app.txt", "safe"))
        );

        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> materializer.materializeAtomically(ProjectTemplateCatalog.VUE_BASIC, target, null)
        );

        assertEquals(TemplateMaterializationException.Reason.UNSAFE_TARGET, exception.reason());
        assertEquals("not a directory", Files.readString(target));
    }

    private void assertReason(TemplateMaterializationException.Reason expected,
                              ProjectTemplateMaterializer materializer,
                              Path target) {
        TemplateMaterializationException exception = assertThrows(
                TemplateMaterializationException.class,
                () -> materializer.materializeAtomically(ProjectTemplateCatalog.VUE_BASIC, target, null)
        );
        assertEquals(expected, exception.reason());
        assertFalse(Files.exists(target));
    }

    private ProjectTemplateMaterializer materializer(TemplateMaterializationProperties properties,
                                                     PathMatchingResourcePatternResolver resolver) {
        return new ProjectTemplateMaterializer(
                properties,
                new ProjectTemplateCatalog(),
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties()),
                resolver
        );
    }

    private PathMatchingResourcePatternResolver resolver(Resource... resources) throws Exception {
        PathMatchingResourcePatternResolver resolver = mock(PathMatchingResourcePatternResolver.class);
        when(resolver.getResources("classpath:project-templates/vue-web-basic/**/*")).thenReturn(resources);
        return resolver;
    }

    private Resource resource(String templateId, String relativePath, String content) throws Exception {
        Resource resource = mock(Resource.class);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        when(resource.exists()).thenReturn(true);
        when(resource.isReadable()).thenReturn(true);
        when(resource.isFile()).thenReturn(false);
        when(resource.getURL()).thenReturn(java.net.URI.create("file:/test/project-templates/" + templateId + "/" + relativePath).toURL());
        when(resource.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(bytes));
        return resource;
    }

    private Path resetRoot(String name) {
        Path root = Path.of("target", "test-workspaces", "template-materializer", name)
                .toAbsolutePath()
                .normalize();
        FileUtil.del(root.toFile());
        return root;
    }
}