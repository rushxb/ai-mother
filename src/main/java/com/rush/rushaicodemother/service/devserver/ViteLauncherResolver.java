package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerRuntimeProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * 解析项目本地 Vite 入口，并生成不经过 shell 的 Node.js 命令。
 */
@Component
public class ViteLauncherResolver {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";

    private final String nodeExecutable;

    @Autowired
    public ViteLauncherResolver(DevServerRuntimeProperties properties) {
        this(properties.getNodeExecutable());
    }

    ViteLauncherResolver(String nodeExecutable) {
        if (nodeExecutable == null || nodeExecutable.isBlank() || nodeExecutable.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Node.js 可执行文件配置无效");
        }
        this.nodeExecutable = nodeExecutable.strip();
    }

    public List<String> resolve(Path projectDirectory, int port) {
        if (projectDirectory == null || port < 1 || port > 65535) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.INVALID_LAUNCHER,
                    "Dev Server 启动参数无效"
            );
        }
        Path nodeModules = projectDirectory.resolve("node_modules").normalize();
        if (Files.isSymbolicLink(nodeModules)
                || !Files.isDirectory(nodeModules, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidLauncher("项目缺少安全的 node_modules 目录");
        }

        Path viteEntry = nodeModules.resolve("vite/bin/vite.js").normalize();
        try {
            Path realNodeModules = nodeModules.toRealPath();
            Path realViteEntry = viteEntry.toRealPath();
            if (!realViteEntry.startsWith(realNodeModules)
                    || !Files.isRegularFile(realViteEntry, LinkOption.NOFOLLOW_LINKS)) {
                throw invalidLauncher("Vite 启动入口超出当前项目 node_modules");
            }
            return List.of(
                    nodeExecutable,
                    realViteEntry.toString(),
                    "--host", LOOPBACK_ADDRESS,
                    "--port", String.valueOf(port),
                    "--strictPort"
            );
        } catch (IOException exception) {
            throw new DevServerStartException(
                    DevServerStartException.Reason.INVALID_LAUNCHER,
                    "项目缺少可用的本地 Vite 启动入口",
                    exception
            );
        }
    }

    private DevServerStartException invalidLauncher(String message) {
        return new DevServerStartException(DevServerStartException.Reason.INVALID_LAUNCHER, message);
    }
}
