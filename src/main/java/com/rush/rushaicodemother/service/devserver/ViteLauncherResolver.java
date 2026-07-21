package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.infrastructure.process.NodeToolchain;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 解析项目本地 Vite 入口，并生成不经过 shell 的 Node.js 命令。
 */
@Component
public class ViteLauncherResolver {

    private static final String LOOPBACK_ADDRESS = "127.0.0.1";
    private static final String PREVIEW_LAUNCHER_SCRIPT = """
            const args = process.argv.slice(1);
            const option = (name) => {
              const index = args.indexOf(name);
              return index >= 0 ? args[index + 1] : undefined;
            };
            const host = option('--host');
            const port = Number(option('--port'));
            const previewBase = option('--base');
            if (!host || !Number.isInteger(port) || port < 1 || port > 65535
                || !previewBase || !previewBase.startsWith('/') || !previewBase.endsWith('/')) {
              throw new Error('Invalid AI Preview Vite launcher arguments');
            }
            const { createServer } = await import('vite');
            const previewRoutingPlugin = {
              name: 'ai-mother-preview-routing',
              enforce: 'post',
              config(config) {
                config.base = previewBase;
                config.server ??= {};
                config.server.host = host;
                config.server.port = port;
                config.server.strictPort = true;
                const currentHmr = config.server.hmr;
                config.server.hmr = currentHmr && typeof currentHmr === 'object'
                  && currentHmr.overlay === false ? { overlay: false } : {};
                const proxy = config.server.proxy;
                if (!proxy || typeof proxy !== 'object') return;
                for (const [context, rawOptions] of Object.entries(proxy)) {
                  const options = typeof rawOptions === 'string'
                    ? { target: rawOptions }
                    : rawOptions;
                  if (!options || typeof options !== 'object') continue;
                  const originalBypass = options.bypass;
                  options.bypass = async (request, response, proxyOptions) => {
                    const requestUrl = typeof request.url === 'string' ? request.url : '';
                    if (requestUrl === previewBase.slice(0, -1)
                        || requestUrl.startsWith(previewBase)) {
                      return requestUrl;
                    }
                    return typeof originalBypass === 'function'
                      ? await originalBypass(request, response, proxyOptions)
                      : undefined;
                  };
                  proxy[context] = options;
                }
              }
            };
            const server = await createServer({
              root: process.cwd(),
              plugins: [previewRoutingPlugin]
            });
            const shutdown = async () => {
              await server.close();
              process.exit(0);
            };
            process.once('SIGINT', shutdown);
            process.once('SIGTERM', shutdown);
            await server.listen();
            server.printUrls();
            """;

    private final NodeToolchain nodeToolchain;
    private final DevServerPreviewPathFactory previewPathFactory;

    public ViteLauncherResolver(NodeToolchain nodeToolchain,
                                DevServerPreviewPathFactory previewPathFactory) {
        this.nodeToolchain = Objects.requireNonNull(nodeToolchain, "nodeToolchain must not be null");
        this.previewPathFactory = Objects.requireNonNull(
                previewPathFactory, "previewPathFactory must not be null");
    }

    public List<String> resolve(Path projectDirectory, int port, Long appId) {
        if (projectDirectory == null || port < 1 || port > 65535
                || appId == null || appId <= 0) {
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
                    nodeToolchain.nodeExecutable(),
                    "--input-type=module",
                    "--eval", PREVIEW_LAUNCHER_SCRIPT,
                    "--",
                    "--host", LOOPBACK_ADDRESS,
                    "--port", String.valueOf(port),
                    "--strictPort",
                    "--base", previewPathFactory.publicBasePath(appId)
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
