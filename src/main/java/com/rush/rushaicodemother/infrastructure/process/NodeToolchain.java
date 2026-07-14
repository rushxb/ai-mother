package com.rush.rushaicodemother.infrastructure.process;

import com.rush.rushaicodemother.config.NodeToolchainProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Objects;

/**
 * 统一解析 Node.js 与 pnpm 可执行文件，Windows 默认使用可直接启动的扩展名。
 */
@Component
public class NodeToolchain {

    private final String nodeExecutable;
    private final String pnpmExecutable;

    @Autowired
    public NodeToolchain(NodeToolchainProperties properties) {
        this(properties, isWindowsOperatingSystem());
    }

    NodeToolchain(NodeToolchainProperties properties, boolean windows) {
        Objects.requireNonNull(properties, "properties must not be null");
        this.nodeExecutable = resolveExecutable(properties.getNodeExecutable(), "node", "node.exe", windows);
        this.pnpmExecutable = resolveExecutable(properties.getPnpmExecutable(), "pnpm", "pnpm.cmd", windows);
    }

    public String nodeExecutable() {
        return nodeExecutable;
    }

    public String pnpmExecutable() {
        return pnpmExecutable;
    }

    private String resolveExecutable(
            String configuredExecutable,
            String portableDefault,
            String windowsDefault,
            boolean windows
    ) {
        if (configuredExecutable == null
                || configuredExecutable.isBlank()
                || configuredExecutable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Node.js 工具链可执行文件配置无效");
        }
        String normalized = configuredExecutable.strip();
        return windows && portableDefault.equalsIgnoreCase(normalized)
                ? windowsDefault
                : normalized;
    }

    private static boolean isWindowsOperatingSystem() {
        return System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT)
                .contains("windows");
    }
}
