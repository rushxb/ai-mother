package com.rush.rushaicodemother.service;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Vue 开发服务器管理器
 * 负责管理每个 Vue 应用的 npm run dev 进程
 */
@Service
@Slf4j
public class DevServerManager {

    /**
     * 端口范围配置
     */
    private static final int PORT_RANGE_START = 10000;
    private static final int PORT_RANGE_END = 60000;

    /**
     * 单用户最大并发 dev server 数量
     */
    private static final int MAX_DEV_SERVERS_PER_USER = 3;

    /**
     * 应用 dev server 进程映射：appId -> Process
     */
    private final Map<Long, Process> runningProcesses = new ConcurrentHashMap<>();

    /**
     * 应用端口映射：appId -> port
     */
    private final Map<Long, Integer> appPorts = new ConcurrentHashMap<>();

    /**
     * 用户应用计数：userId -> Set<appId>
     */
    private final Map<Long, Set<Long>> userAppServers = new ConcurrentHashMap<>();

    /**
     * 端口分配原子计数器
     */
    private final AtomicInteger portCounter = new AtomicInteger(PORT_RANGE_START);

    /**
     * 正在安装依赖的项目目录锁
     */
    private final Map<String, Object> installLocks = new ConcurrentHashMap<>();

    /**
     * 为应用分配一个可用端口
     *
     * @param appId 应用ID
     * @return 分配的端口号
     */
    public int allocatePort(Long appId) {
        if (appPorts.containsKey(appId)) {
            return appPorts.get(appId);
        }

        // 尝试从计数器开始找可用端口
        int attempts = 0;
        int maxAttempts = PORT_RANGE_END - PORT_RANGE_START;

        while (attempts < maxAttempts) {
            int port = portCounter.getAndIncrement();
            if (port > PORT_RANGE_END) {
                portCounter.set(PORT_RANGE_START);
                port = PORT_RANGE_START;
            }

            if (isPortAvailable(port) && !isPortAllocated(port)) {
                appPorts.put(appId, port);
                log.info("为应用 {} 分配端口 {}", appId, port);
                return port;
            }
            attempts++;
        }

        throw new BusinessException(ErrorCode.SYSTEM_ERROR, "无法分配可用端口，端口池已满");
    }

    /**
     * 释放应用的端口分配
     *
     * @param appId 应用ID
     */
    public void releasePort(Long appId) {
        Integer port = appPorts.remove(appId);
        if (port != null) {
            log.info("释放应用 {} 的端口 {}", appId, port);
        }
    }

    /**
     * 启动应用的 dev server
     *
     * @param app      应用实体
     * @param userId   用户ID
     * @return 分配的端口号
     */
    public int startDevServer(App app, Long userId) {
        Long appId = app.getId();

        // 检查是否已经在运行
        if (isRunning(appId)) {
            log.info("应用 {} 的 dev server 已在运行", appId);
            // appPorts 可能没有（端口来自数据库），用 app.getDevServerPort() 兜底
            Integer port = appPorts.get(appId);
            if (port == null && app.getDevServerPort() != null) {
                port = app.getDevServerPort();
                appPorts.put(appId, port); // 同步到 map
            }
            if (port == null) {
                // 端口信息丢失，杀掉残留进程后重新启动
                log.warn("应用 {} 的 dev server 在运行但端口信息丢失，将重新启动", appId);
                Process stale = runningProcesses.remove(appId);
                if (stale != null) {
                    stale.destroyForcibly();
                }
            } else {
                // 确保引导脚本已注入（处理已运行但未注入的情况）
                String projectPath = getProjectPath(app);
                File projectDir = new File(projectPath);
                if (projectDir.exists() && projectDir.isDirectory()) {
                    injectVisualEditorBootstrap(projectDir);
                }
                return port;
            }
        }

        // 检查用户并发限制
        checkUserLimit(userId);

        // 检查是否为 Vue 项目
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
        if (codeGenType != CodeGenTypeEnum.VUE_PROJECT && codeGenType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "仅 Vue 项目支持 dev server 预览");
        }

        // 获取或分配端口
        int port = app.getDevServerPort() != null ? app.getDevServerPort() : allocatePort(appId);
        appPorts.put(appId, port); // 确保 appPorts 有记录

        // 获取项目路径
        String projectPath = getProjectPath(app);
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "项目目录不存在，请先生成代码");
        }

        // 检查 package.json 是否存在
        File packageJson = new File(projectDir, "package.json");
        if (!packageJson.exists()) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "项目缺少 package.json");
        }

        // 启动 dev server
        Process process = executeDevServer(projectDir, port);
        runningProcesses.put(appId, process);

        // 记录用户应用
        userAppServers.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(appId);

        log.info("应用 {} 的 dev server 已启动，端口: {}", appId, port);
        return port;
    }

    /**
     * 停止应用的 dev server
     *
     * @param appId  应用ID
     * @param userId 用户ID
     */
    public void stopDevServer(Long appId, Long userId) {
        Process process = runningProcesses.remove(appId);
        if (process != null) {
            process.destroyForcibly();
            log.info("应用 {} 的 dev server 已停止", appId);
        }

        // 更新用户应用记录
        Set<Long> userApps = userAppServers.get(userId);
        if (userApps != null) {
            userApps.remove(appId);
            if (userApps.isEmpty()) {
                userAppServers.remove(userId);
            }
        }
    }

    /**
     * 检查应用的 dev server 是否在运行
     *
     * @param appId 应用ID
     * @return 是否运行中
     */
    public boolean isRunning(Long appId) {
        Process process = runningProcesses.get(appId);
        if (process == null) {
            return false;
        }
        if (!process.isAlive()) {
            runningProcesses.remove(appId);
            return false;
        }
        return true;
    }

    /**
     * 获取应用的 dev server 端口
     *
     * @param appId 应用ID
     * @return 端口号，未分配返回 null
     */
    public Integer getPort(Long appId) {
        return appPorts.get(appId);
    }

    /**
     * 检查端口是否可用（未被占用）
     *
     * @param port 端口号
     * @return 是否可用
     */
    private boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            serverSocket.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 检查端口是否已被分配给其他应用
     *
     * @param port 端口号
     * @return 是否已分配
     */
    private boolean isPortAllocated(int port) {
        return appPorts.containsValue(port);
    }

    /**
     * 检查用户的 dev server 并发限制
     *
     * @param userId 用户ID
     */
    private void checkUserLimit(Long userId) {
        Set<Long> userApps = userAppServers.get(userId);
        if (userApps != null && userApps.size() >= MAX_DEV_SERVERS_PER_USER) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR,
                    String.format("单用户最多同时运行 %d 个 dev server，请先停止其他应用的预览", MAX_DEV_SERVERS_PER_USER));
        }
    }

    /**
     * 获取项目路径
     *
     * @param app 应用实体
     * @return 项目路径
     */
    private String getProjectPath(App app) {
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + app.getId();
        String projectPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;

        // 全栈项目的前端目录
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (codeGenTypeEnum == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            projectPath = projectPath + File.separator + "frontend";
        }

        return projectPath;
    }

    /**
     * 执行 npm run dev 命令
     *
     * @param projectDir 项目目录
     * @param port       端口号
     * @return 进程
     */
    private Process executeDevServer(File projectDir, int port) {
        try {
            String npmCommand = isWindows() ? "pnpm.cmd" : "pnpm";

            // 1. 确保依赖已安装
            ensureDependenciesInstalled(projectDir, npmCommand);

            // 2. 启动 dev server
            // 直接调用 node_modules/.bin/vite，避免 pnpm 的 -- 分隔符在 Windows 上不生效
            ProcessBuilder processBuilder;
            if (isWindows()) {
                File viteBin = new File(projectDir, "node_modules\\.bin\\vite.cmd");
                if (viteBin.exists()) {
                    processBuilder = new ProcessBuilder(
                            viteBin.getAbsolutePath(), "--port", String.valueOf(port));
                } else {
                    // fallback: 用 cmd /c 调用 npx
                    String fullCommand = String.format("npx vite --port %d", port);
                    processBuilder = new ProcessBuilder("cmd", "/c", fullCommand);
                }
            } else {
                File viteBin = new File(projectDir, "node_modules/.bin/vite");
                if (viteBin.exists()) {
                    processBuilder = new ProcessBuilder(
                            viteBin.getAbsolutePath(), "--port", String.valueOf(port));
                } else {
                    processBuilder = new ProcessBuilder(
                            npmCommand, "run", "dev", "--", "--port", String.valueOf(port));
                }
            }
            processBuilder.directory(projectDir);
            processBuilder.redirectErrorStream(true);

            // 设置环境变量
            Map<String, String> env = processBuilder.environment();
            env.put("NO_UPDATE_NOTIFIER", "1");
            env.put("NPM_CONFIG_AUDIT", "false");
            env.put("NPM_CONFIG_FUND", "false");

            // 设置允许跨域（CORS）的环境变量
            env.put("VITE_CORS_ORIGIN", "*");

            // 2.5 注入可视化编辑器引导脚本到 index.html
            injectVisualEditorBootstrap(projectDir);

            Process process = processBuilder.start();

            // 3. 异步读取进程输出（防止缓冲区满导致进程挂起）
            startOutputDrainer(process);

            // 4. 等待 dev server 启动并检查端口是否可用
            int maxWaitSeconds = 30;
            int waitInterval = 1000; // 1 秒
            for (int i = 0; i < maxWaitSeconds; i++) {
                Thread.sleep(waitInterval);

                // 检查进程是否还活着
                if (!process.isAlive()) {
                    String errorMsg = "dev server 启动失败，进程已退出（exit code: " + process.exitValue() + "）";
                    log.error(errorMsg);
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMsg);
                }

                // 检查端口是否已被占用（说明 dev server 已启动）
                if (!isPortAvailable(port)) {
                    log.info("dev server 已启动，端口 {} 已就绪（等待 {} 秒）", port, i + 1);
                    return process;
                }
            }

            // 超时但进程还在，仍然返回（可能启动较慢）
            log.warn("dev server 启动超时（{} 秒），但进程仍在运行", maxWaitSeconds);
            return process;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException | InterruptedException e) {
            log.error("启动 dev server 失败", e);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "启动 dev server 失败：" + e.getMessage());
        }
    }

    /**
     * 确保项目依赖已安装
     */
    private void ensureDependenciesInstalled(File projectDir, String npmCommand) {
        String projectPath = projectDir.getAbsolutePath();
        Object lock = installLocks.computeIfAbsent(projectPath, k -> new Object());

        synchronized (lock) {
            File nodeModules = new File(projectDir, "node_modules");
            if (nodeModules.exists() && nodeModules.isDirectory()) {
                // 检查 node_modules 是否完整（通过检查 .pnpm 目录或 package-lock.json）
                if (isNodeModulesComplete(projectDir)) {
                    log.info("node_modules 已存在且完整，跳过依赖安装");
                    return;
                }
                log.warn("node_modules 存在但不完整，将重新安装依赖");
            }

            log.info("开始安装依赖...");
            try {
                ProcessBuilder installBuilder = new ProcessBuilder(npmCommand, "install", "--force");
                installBuilder.directory(projectDir);
                installBuilder.redirectErrorStream(true);

                Map<String, String> env = installBuilder.environment();
                env.put("NO_UPDATE_NOTIFIER", "1");
                env.put("NPM_CONFIG_AUDIT", "false");
                env.put("NPM_CONFIG_FUND", "false");

                Process installProcess = installBuilder.start();
                startOutputDrainer(installProcess);

                // 等待安装完成，最多 5 分钟
                boolean finished = installProcess.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
                if (!finished) {
                    installProcess.destroyForcibly();
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "依赖安装超时（5 分钟）");
                }
                if (installProcess.exitValue() != 0) {
                    throw new BusinessException(ErrorCode.SYSTEM_ERROR, "依赖安装失败（exit code: " + installProcess.exitValue() + "）");
                }
                log.info("依赖安装完成");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "依赖安装被中断");
            } catch (IOException e) {
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, "依赖安装失败：" + e.getMessage());
            } finally {
                installLocks.remove(projectPath);
            }
        }
    }

    /**
     * 检查 node_modules 是否完整
     * 通过检查关键目录和文件来判断
     */
    private boolean isNodeModulesComplete(File projectDir) {
        // 检查安装标记文件（VueProjectBuilder 使用此文件跟踪安装状态）
        File stampFile = new File(projectDir, ".ai-code-install.stamp");
        if (!stampFile.exists()) {
            // 如果没有标记文件，检查 node_modules 是否有内容
            File nodeModulesDir = new File(projectDir, "node_modules");
            if (!nodeModulesDir.exists() || !nodeModulesDir.isDirectory()) {
                return false;
            }
            // 检查 node_modules 中是否有文件（防止空目录）
            String[] files = nodeModulesDir.list();
            if (files == null || files.length == 0) {
                return false;
            }
        }

        // 检查 .pnpm 目录（pnpm 的硬链接目录）
        File pnpmDir = new File(projectDir, "node_modules/.pnpm");
        if (!pnpmDir.exists() || !pnpmDir.isDirectory()) {
            return false;
        }

        // 检查 vite 是否已安装
        File viteBin = new File(projectDir, "node_modules/.bin/vite.cmd");
        if (!viteBin.exists()) {
            viteBin = new File(projectDir, "node_modules/.bin/vite");
            if (!viteBin.exists()) {
                return false;
            }
        }

        // 检查 node_modules 中是否有文件（防止空目录）
        String[] files = pnpmDir.list();
        if (files == null || files.length == 0) {
            return false;
        }

        return true;
    }

    /**
     * 异步读取进程输出，防止缓冲区满导致进程挂起
     */
    private void startOutputDrainer(Process process) {
        Thread drainer = new Thread(() -> {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.info("[dev-server] {}", line);
                }
            } catch (IOException e) {
                // 忽略读取错误
            }
        }, "dev-server-output-" + process.hashCode());
        drainer.setDaemon(true);
        drainer.start();
    }

    /**
     * 判断是否为 Windows 系统
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    /**
     * 向生成项目的 index.html 注入可视化编辑器引导脚本
     * 该脚本监听 postMessage 消息，接收父窗口发送的编辑脚本并执行
     * 仅在 index.html 中未包含引导脚本时注入
     */
    private void injectVisualEditorBootstrap(File projectDir) {
        File indexHtml = new File(projectDir, "index.html");
        if (!indexHtml.exists()) {
            return;
        }
        try {
            String content = new String(java.nio.file.Files.readAllBytes(indexHtml.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            // 检查是否已注入
            if (content.contains("visual-editor-bootstrap")) {
                return;
            }
            String bootstrapScript = """
                    <script id="visual-editor-bootstrap">
                    // 可视化编辑器引导脚本：接收父窗口通过 postMessage 发送的编辑脚本
                    window.addEventListener('message', function(event) {
                      var data = event.data;
                      if (!data || !data.type) return;
                      if (data.type === 'INJECT_EDIT_SCRIPT' && data.script) {
                        if (!document.getElementById('visual-edit-script')) {
                          try {
                            var s = document.createElement('script');
                            s.id = 'visual-edit-script';
                            s.textContent = data.script;
                            document.head.appendChild(s);
                          } catch (e) { console.error('注入编辑脚本失败:', e); }
                        }
                      }
                    });
                    </script>
                    """;
            // 在 </head> 前注入
            int headCloseIndex = content.indexOf("</head>");
            if (headCloseIndex >= 0) {
                String newContent = content.substring(0, headCloseIndex) + bootstrapScript + content.substring(headCloseIndex);
                java.nio.file.Files.write(indexHtml.toPath(), newContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                log.info("已注入可视化编辑器引导脚本到 index.html");
            }
        } catch (IOException e) {
            log.warn("注入可视化编辑器引导脚本失败: {}", e.getMessage());
        }
    }

    /**
     * 服务关闭时清理所有进程
     */
    @PreDestroy
    public void destroy() {
        log.info("正在关闭所有 dev server 进程...");
        runningProcesses.forEach((appId, process) -> {
            try {
                process.destroyForcibly();
                log.info("已关闭应用 {} 的 dev server", appId);
            } catch (Exception e) {
                log.error("关闭应用 {} 的 dev server 失败", appId, e);
            }
        });
        runningProcesses.clear();
        appPorts.clear();
        userAppServers.clear();
    }
}
