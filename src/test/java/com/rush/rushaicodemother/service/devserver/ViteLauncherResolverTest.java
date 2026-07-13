package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ViteLauncherResolverTest {

    private Path tempDirectory;


    @BeforeEach
    void setUp() throws Exception {
        tempDirectory = DevServerTestWorkspace.create("vite-launcher");
    }

    @AfterEach
    void tearDown() throws Exception {
        DevServerTestWorkspace.delete(tempDirectory);
    }

    @Test
    void shouldResolveProjectLocalViteWithoutShellFallback() throws IOException {
        Path projectDirectory = createProjectWithVite(tempDirectory.resolve("project"));
        ViteLauncherResolver resolver = new ViteLauncherResolver("fixed-node");

        List<String> command = resolver.resolve(projectDirectory, 5180);

        assertEquals("fixed-node", command.getFirst());
        assertEquals(projectDirectory.resolve("node_modules/vite/bin/vite.js").toRealPath().toString(), command.get(1));
        assertEquals(List.of("--host", "127.0.0.1", "--port", "5180", "--strictPort"),
                command.subList(2, command.size()));
        String joined = String.join(" ", command).toLowerCase();
        assertFalse(joined.contains("cmd /c"));
        assertFalse(joined.contains("npx"));
        assertFalse(joined.contains("pnpm run dev"));
    }

    @Test
    void shouldRejectSymbolicNodeModulesDirectory() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path externalNodeModules = Files.createDirectories(tempDirectory.resolve("external-node-modules"));
        boolean linked = createSymbolicLink(projectDirectory.resolve("node_modules"), externalNodeModules);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> new ViteLauncherResolver("node").resolve(projectDirectory, 5180)
        );

        assertEquals(DevServerStartException.Reason.INVALID_LAUNCHER, exception.reason());
    }

    @Test
    void shouldRejectViteEntryWhoseRealTargetEscapesNodeModules() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        Path viteBin = Files.createDirectories(projectDirectory.resolve("node_modules/vite/bin"));
        Path externalVite = Files.writeString(tempDirectory.resolve("external-vite.js"), "console.log('vite')");
        boolean linked = createSymbolicLink(viteBin.resolve("vite.js"), externalVite);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> new ViteLauncherResolver("node").resolve(projectDirectory, 5180)
        );

        assertEquals(DevServerStartException.Reason.INVALID_LAUNCHER, exception.reason());
    }

    @Test
    void shouldRejectInvalidArguments() {
        ViteLauncherResolver resolver = new ViteLauncherResolver("node");

        assertThrows(DevServerStartException.class, () -> resolver.resolve(null, 5180));
        assertThrows(DevServerStartException.class, () -> resolver.resolve(tempDirectory, 0));
        assertThrows(IllegalArgumentException.class, () -> new ViteLauncherResolver("node" + '\0' + "evil"));
    }

    private Path createProjectWithVite(Path projectDirectory) throws IOException {
        Path viteEntry = projectDirectory.resolve("node_modules/vite/bin/vite.js");
        Files.createDirectories(viteEntry.getParent());
        Files.writeString(viteEntry, "console.log('vite')");
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
