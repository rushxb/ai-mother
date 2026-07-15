package com.rush.rushaicodemother.orchestration.template;

import cn.hutool.core.io.FileUtil;
import com.rush.rushaicodemother.config.WorkspaceFileSystemProperties;
import com.rush.rushaicodemother.infrastructure.filesystem.WorkspaceFileSystemService;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplatePreWarmServiceTest {

    @Test
    void shouldCopyRegisteredModulesThroughBoundedWorkspaceCopy() throws Exception {
        Path root = resetRoot("copy");
        Path cachedModules = root.resolve("cache/node_modules");
        Path target = root.resolve("target");
        Files.createDirectories(cachedModules);
        Files.createDirectories(target);
        Files.writeString(cachedModules.resolve("package.js"), "module.exports = true;");
        TemplatePreWarmService service = service();
        service.registerPreWarmedModules(ProjectTemplateCatalog.VUE_BASIC, cachedModules);

        assertTrue(service.copyPreWarmedModules(ProjectTemplateCatalog.VUE_BASIC, target));
        assertTrue(Files.isRegularFile(target.resolve("node_modules/package.js")));
    }

    @Test
    void shouldRejectUnknownCacheKeyAndOrdinaryFileCache() throws Exception {
        Path root = resetRoot("invalid-cache");
        Files.createDirectories(root);
        Path ordinaryFile = Files.writeString(root.resolve("node_modules"), "not a directory");
        TemplatePreWarmService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.registerPreWarmedModules("arbitrary-template", ordinaryFile));
        assertThrows(IOException.class, () -> service.registerPreWarmedModules(ProjectTemplateCatalog.VUE_BASIC, ordinaryFile));
    }

    @Test
    void shouldRejectSymbolicLinkAtTargetNodeModulesBoundary() throws Exception {
        Path root = resetRoot("target-link");
        Path cachedModules = root.resolve("cache/node_modules");
        Path target = root.resolve("target");
        Path external = root.resolve("external");
        Files.createDirectories(cachedModules);
        Files.createDirectories(target);
        Files.createDirectories(external);
        try {
            Files.createSymbolicLink(target.resolve("node_modules"), external.toAbsolutePath());
        } catch (UnsupportedOperationException | IOException | SecurityException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }
        TemplatePreWarmService service = service();
        service.registerPreWarmedModules(ProjectTemplateCatalog.VUE_BASIC, cachedModules);

        assertThrows(IOException.class, () -> service.copyPreWarmedModules(ProjectTemplateCatalog.VUE_BASIC, target));
        assertFalse(Files.exists(external.resolve("package.js")));
    }

    private TemplatePreWarmService service() {
        return new TemplatePreWarmService(
                new ProjectTemplateCatalog(),
                new WorkspaceFileSystemService(new WorkspaceFileSystemProperties())
        );
    }

    private Path resetRoot(String name) {
        Path root = Path.of("target", "test-workspaces", "template-pre-warm", name);
        FileUtil.del(root.toFile());
        return root.toAbsolutePath().normalize();
    }
}