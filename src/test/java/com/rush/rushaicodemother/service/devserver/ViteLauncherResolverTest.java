package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import com.rush.rushaicodemother.security.workspace.GeneratedNodeWorkspaceValidator;
import com.rush.rushaicodemother.security.workspace.GeneratedWorkspaceTrustPolicy;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        ViteLauncherResolver resolver = createResolver("fixed-node");

        List<String> command = resolver.resolve(projectDirectory, 5180, 21L);

        assertEquals("fixed-node", command.getFirst());
        assertEquals("--input-type=module", command.get(1));
        assertEquals("--eval", command.get(2));
        assertTrue(command.get(3).contains("await import('vite')"));
        assertTrue(command.get(3).contains("ai-mother-preview-routing"));
        assertEquals(List.of(
                        "--",
                        "--host", "127.0.0.1",
                        "--port", "5180",
                        "--strictPort",
                        "--base", "/api/app/dev-server/proxy/21/"
                ),
                command.subList(4, command.size()));
        String joined = String.join(" ", command).toLowerCase();
        assertFalse(joined.contains("cmd /c"));
        assertFalse(joined.contains("npx"));
        assertFalse(joined.contains("pnpm run dev"));
    }

    @Test
    void shouldRejectUntrustedWorkspaceBeforeResolvingRuntimeLauncher() throws IOException {
        Path projectDirectory = createProjectWithVite(tempDirectory.resolve("untrusted-project"));
        Files.writeString(
                projectDirectory.resolve(".npmrc"),
                "registry=https://attacker.invalid/"
        );

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> createResolver("node").resolve(projectDirectory, 5180, 21L)
        );

        assertEquals(DevServerStartException.Reason.INVALID_LAUNCHER, exception.reason());
        assertTrue(exception.getMessage().contains(
                "generated_workspace_forbidden_control_file:.npmrc"));
    }

    @Test
    void shouldRejectSymbolicNodeModulesDirectory() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        writeSafeManifest(projectDirectory);
        Path externalNodeModules = Files.createDirectories(tempDirectory.resolve("external-node-modules"));
        boolean linked = createSymbolicLink(projectDirectory.resolve("node_modules"), externalNodeModules);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> createResolver("node").resolve(projectDirectory, 5180, 21L)
        );

        assertEquals(DevServerStartException.Reason.INVALID_LAUNCHER, exception.reason());
    }

    @Test
    void shouldRejectViteEntryWhoseRealTargetEscapesNodeModules() throws IOException {
        Path projectDirectory = Files.createDirectories(tempDirectory.resolve("project"));
        writeSafeManifest(projectDirectory);
        Path viteBin = Files.createDirectories(projectDirectory.resolve("node_modules/vite/bin"));
        Path externalVite = Files.writeString(tempDirectory.resolve("external-vite.js"), "console.log('vite')");
        boolean linked = createSymbolicLink(viteBin.resolve("vite.js"), externalVite);
        assumeTrue(linked, "Symbolic links are not supported in this environment");

        DevServerStartException exception = assertThrows(
                DevServerStartException.class,
                () -> createResolver("node").resolve(projectDirectory, 5180, 21L)
        );

        assertEquals(DevServerStartException.Reason.INVALID_LAUNCHER, exception.reason());
    }

    @Test
    void shouldRejectInvalidArguments() {
        ViteLauncherResolver resolver = createResolver("node");

        assertThrows(DevServerStartException.class, () -> resolver.resolve(null, 5180, 21L));
        assertThrows(DevServerStartException.class, () -> resolver.resolve(tempDirectory, 0, 21L));
        assertThrows(DevServerStartException.class, () -> resolver.resolve(tempDirectory, 5180, null));
    }

    private ViteLauncherResolver createResolver(String nodeExecutable) {
        NodeToolchain nodeToolchain = mock(NodeToolchain.class);
        when(nodeToolchain.nodeExecutable()).thenReturn(nodeExecutable);
        return new ViteLauncherResolver(
                nodeToolchain,
                new DevServerPreviewPathFactory("/api"),
                new GeneratedNodeWorkspaceValidator(new GeneratedWorkspaceTrustPolicy())
        );
    }

    private Path createProjectWithVite(Path projectDirectory) throws IOException {
        Path viteEntry = projectDirectory.resolve("node_modules/vite/bin/vite.js");
        Files.createDirectories(viteEntry.getParent());
        Files.writeString(viteEntry, "console.log('vite')");
        writeSafeManifest(projectDirectory);
        return projectDirectory;
    }

    private void writeSafeManifest(Path projectDirectory) throws IOException {
        Files.writeString(
                projectDirectory.resolve("package.json"),
                "{\"packageManager\":\"pnpm@9.15.0\"}"
        );
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
