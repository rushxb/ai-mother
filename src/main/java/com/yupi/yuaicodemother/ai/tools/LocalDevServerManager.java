package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 本地开发服务器管理器
 */
@Slf4j
@Component
public class LocalDevServerManager {

    private static final int STARTUP_WAIT_SECONDS = 25;
    private static final int MAX_LOG_CHARS = 16000;

    private final Map<Long, DevServerSession> sessionMap = new ConcurrentHashMap<>();

    public synchronized String startServer(Long appId, Path projectPath) {
        DevServerSession currentSession = sessionMap.get(appId);
        if (currentSession != null && currentSession.process().isAlive()) {
            return renderSessionReport("开发服务器已在运行", currentSession, true);
        }
        stopServerInternal(appId);
        Path packageJsonPath = projectPath.resolve("package.json");
        if (!packageJsonPath.toFile().exists()) {
            return "错误：package.json 不存在，无法启动开发服务器";
        }
        JSONObject packageJson = JSONUtil.parseObj(FileUtil.readUtf8String(packageJsonPath.toFile()));
        JSONObject scripts = packageJson.getJSONObject("scripts");
        if (scripts == null || !scripts.containsKey("dev")) {
            return "错误：package.json 中未找到 dev 脚本";
        }
        int port = findAvailablePort(resolvePreferredPort(appId));
        try {
            Process process = new ProcessBuilder(
                    NpmCommandSupport.npmCommand(),
                    "run",
                    "dev",
                    "--",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    String.valueOf(port)
            ).directory(projectPath.toFile()).redirectErrorStream(true).start();
            DevServerSession session = new DevServerSession(
                    appId,
                    projectPath,
                    port,
                    "http://127.0.0.1:" + port + "/",
                    process,
                    LocalDateTime.now(),
                    new StringBuilder()
            );
            sessionMap.put(appId, session);
            Thread.ofVirtual().name("dev-server-log-" + appId).start(() -> consumeLogs(session));
            waitForStartup(session);
            return renderSessionReport("开发服务器启动结果", session, true);
        } catch (Exception e) {
            log.error("启动开发服务器失败，appId: {}", appId, e);
            return "启动开发服务器失败: " + e.getMessage();
        }
    }

    public synchronized String restartServer(Long appId, Path projectPath) {
        stopServerInternal(appId);
        return startServer(appId, projectPath);
    }

    public String getServerStatus(Long appId) {
        DevServerSession session = sessionMap.get(appId);
        if (session == null) {
            return "当前没有运行中的开发服务器";
        }
        return renderSessionReport("开发服务器状态", session, true);
    }

    public synchronized String stopServer(Long appId) {
        DevServerSession session = sessionMap.get(appId);
        if (session == null) {
            return "当前没有运行中的开发服务器";
        }
        stopServerInternal(appId);
        return "开发服务器已停止，端口: " + session.port() + "，URL: " + session.url();
    }

    public DevServerSession getSession(Long appId) {
        return sessionMap.get(appId);
    }

    @PreDestroy
    public void destroy() {
        sessionMap.keySet().forEach(this::stopServerInternal);
    }

    private void consumeLogs(DevServerSession session) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(session.process().getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                appendLog(session, line);
            }
        } catch (Exception e) {
            appendLog(session, "[日志采集异常] " + e.getMessage());
        }
    }

    private void waitForStartup(DevServerSession session) {
        long deadline = System.currentTimeMillis() + STARTUP_WAIT_SECONDS * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!session.process().isAlive()) {
                return;
            }
            if (isHttpReachable(session.url())) {
                return;
            }
            sleepMillis(1000);
        }
    }

    private synchronized void stopServerInternal(Long appId) {
        DevServerSession session = sessionMap.remove(appId);
        if (session == null) {
            return;
        }
        Process process = session.process();
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private String renderSessionReport(String title, DevServerSession session, boolean includeLogs) {
        boolean alive = session.process().isAlive();
        boolean reachable = alive && isHttpReachable(session.url());
        StringBuilder builder = new StringBuilder();
        builder.append(title).append('\n');
        builder.append("状态: ").append(alive ? "运行中" : "已退出").append('\n');
        builder.append("URL: ").append(session.url()).append('\n');
        builder.append("端口: ").append(session.port()).append('\n');
        builder.append("目录: ").append(session.projectPath()).append('\n');
        builder.append("启动时间: ").append(session.startedAt()).append('\n');
        builder.append("可访问: ").append(reachable ? "是" : "否").append('\n');
        if (!alive) {
            try {
                builder.append("退出码: ").append(session.process().exitValue()).append('\n');
            } catch (IllegalThreadStateException ignored) {
            }
        }
        if (includeLogs) {
            builder.append("\n最近日志:\n").append(recentLogs(session));
        }
        return builder.toString().trim();
    }

    private void appendLog(DevServerSession session, String line) {
        synchronized (session.logBuffer()) {
            session.logBuffer().append(line).append('\n');
            int overflow = session.logBuffer().length() - MAX_LOG_CHARS;
            if (overflow > 0) {
                session.logBuffer().delete(0, overflow);
            }
        }
    }

    private String recentLogs(DevServerSession session) {
        synchronized (session.logBuffer()) {
            return StrUtil.blankToDefault(session.logBuffer().toString().trim(), "(暂无日志)");
        }
    }

    private boolean isHttpReachable(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setConnectTimeout((int) Duration.ofSeconds(2).toMillis());
            connection.setReadTimeout((int) Duration.ofSeconds(2).toMillis());
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code >= 200 && code < 500;
        } catch (Exception e) {
            return false;
        }
    }

    private int resolvePreferredPort(Long appId) {
        return 3500 + (int) (Math.abs(appId % 1000));
    }

    private int findAvailablePort(int preferredPort) {
        for (int port = preferredPort; port < preferredPort + 20; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        throw new IllegalStateException("未找到可用端口");
    }

    private boolean isPortAvailable(int port) {
        try (ServerSocket ignored = new ServerSocket(port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public record DevServerSession(Long appId, Path projectPath, int port, String url, Process process,
                                   LocalDateTime startedAt, StringBuilder logBuffer) {
    }
}
