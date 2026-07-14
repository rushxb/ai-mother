package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.NodeToolchainProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NodeToolchainTest {

    @Test
    void shouldResolvePortableDefaultsToWindowsLaunchers() {
        NodeToolchain toolchain = new NodeToolchain(new NodeToolchainProperties(), true);

        assertEquals("node.exe", toolchain.nodeExecutable());
        assertEquals("pnpm.cmd", toolchain.pnpmExecutable());
    }

    @Test
    void shouldPreserveExplicitExecutablePathsOnWindows() {
        NodeToolchainProperties properties = new NodeToolchainProperties();
        properties.setNodeExecutable("D:\\tools\\node-custom.exe");
        properties.setPnpmExecutable("D:\\tools\\pnpm-custom.cmd");

        NodeToolchain toolchain = new NodeToolchain(properties, true);

        assertEquals("D:\\tools\\node-custom.exe", toolchain.nodeExecutable());
        assertEquals("D:\\tools\\pnpm-custom.cmd", toolchain.pnpmExecutable());
    }

    @Test
    void shouldKeepPortableDefaultsOnNonWindowsSystems() {
        NodeToolchain toolchain = new NodeToolchain(new NodeToolchainProperties(), false);

        assertEquals("node", toolchain.nodeExecutable());
        assertEquals("pnpm", toolchain.pnpmExecutable());
    }
}
