package com.rush.rushaicodemother.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * pnpm install 统一服务
 * <p>
 * 解决的核心问题：
 * 1. VueProjectBuilder 和 DevServerManager 两处重复的 install 逻辑
 * 2. pnpm --force 首次使用损坏可选原生依赖（@rollup/rollup-win32-x64-msvc 的 package.json 丢失）
 * 3. isNodeModulesComplete 无法检测损坏的原生包
 * 4. EPERM 处理被动（失败后杀进程）而非主动
 * <p>
 * 设计原则：
 * - 首次安装不使用 --force，避免损坏原生依赖
 * - 主动停止 dev server 再安装（而非被动 EPERM 处理）
 * - 安装后验证关键原生包完整性
 * - 自动修复损坏的原生包
 */
@Slf4j
@Service
public class PnpmInstallService {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * Windows 平台原生包清单（pnpm --force 可能损坏这些包）
     * key: pnpm .pnpm 目录中的包前缀
     * value: 原生包在 node_modules 中的路径
     */
    private static final String[][] CRITICAL_NATIVE_PACKAGES = {
            {"@rollup+rollup-win32", "@rollup/rollup-win32-x64-msvc"},
            {"@esbuild+win32", "@esbuild/win32-x64"},
    };

    /** 每个项目的安装锁，防止并发安装 */
    private final Map<String, Object> installLocks = new ConcurrentHashMap<>();

    /**
     * 安装结果
     */
    public record InstallResult(boolean success, String output, String errorDetail) {

        public static InstallResult success(String output) {
            return new InstallResult(true, output, null);
        }

        public static InstallResult failed(String output, String errorDetail) {
            return new InstallResult(false, output, errorDetail);
        }
    }

    /**
     * 确保依赖已安装（幂等）
     * <p>
     * 流程：
     * 1. 检查 node_modules 是否完整（含原生包验证）
     * 2. 如果完整，跳过安装
     * 3. 如果不完整或损坏，执行安装
     * 4. 安装后再次验证，失败则 --force 重试
     *
     * @param projectDir 项目根目录
     * @return 安装结果
     */
    public InstallResult ensureInstalled(File projectDir) {
        return ensureInstalled(projectDir, false);
    }

    /**
     * 确保依赖已安装
     *
     * @param projectDir    项目根目录
     * @param forceReinstall 是否强制重装（忽略完整性检查）
     * @return 安装结果
     */
    public InstallResult ensureInstalled(File projectDir, boolean forceReinstall) {
        String projectPath = projectDir.getAbsolutePath();
        Object lock = installLocks.computeIfAbsent(projectPath, k -> new Object());

        synchronized (lock) {
            try {
                if (!forceReinstall && isNodeModulesComplete(projectDir)) {
                    log.info("node_modules 已存在且完整，跳过安装: {}", projectPath);
                    return InstallResult.success("依赖已存在且完整，跳过安装");
                }

                log.info("开始安装依赖: {}", projectPath);
                InstallResult result = doInstall(projectDir, false);

                if (result.success()) {
                    // 安装后验证原生包完整性
                    if (areNativePackagesComplete(projectDir)) {
                        log.info("依赖安装完成且原生包完整: {}", projectPath);
                        return result;
                    }
                    // 原生包损坏，清理后 --force 重试
                    log.warn("安装成功但原生包不完整，清理后 --force 重试: {}", projectPath);
                    cleanCorruptedNativePackages(projectDir);
                    result = doInstall(projectDir, true);
                } else if (isPermissionError(result.output())) {
                    // EPERM：杀掉锁定进程后 --force 重试
                    log.warn("检测到 EPERM 错误，释放文件锁后重试: {}", projectPath);
                    killLockingProcesses(projectDir);
                    sleep(2000);
                    result = doInstall(projectDir, true);
                } else {
                    // 其他失败：--force 重试
                    log.warn("首次安装失败，--force 重试: {}", projectPath);
                    result = doInstall(projectDir, true);
                }

                // 最终验证
                if (result.success() && !areNativePackagesComplete(projectDir)) {
                    log.warn("重试后原生包仍不完整，清理并最终重试: {}", projectPath);
                    cleanCorruptedNativePackages(projectDir);
                    result = doInstall(projectDir, true);
                }

                return result;
            } finally {
                installLocks.remove(projectPath);
            }
        }
    }

    /**
     * 执行 pnpm install
     *
     * @param projectDir 项目目录
     * @param force      是否使用 --force
     * @return 安装结果
     */
    private InstallResult doInstall(File projectDir, boolean force) {
        List<String> cmd = buildPnpmCommand(force);
        log.info("执行: {} (目录: {})", String.join(" ", cmd), projectDir.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            pb.environment().put("NO_UPDATE_NOTIFIER", "1");
            pb.environment().put("NPM_CONFIG_AUDIT", "false");
            pb.environment().put("NPM_CONFIG_FUND", "false");

            Process process = pb.start();
            String output = readProcessOutput(process);
            boolean finished = process.waitFor(DEFAULT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (!finished) {
                process.destroyForcibly();
                return InstallResult.failed(output, "安装超时（" + DEFAULT_TIMEOUT_SECONDS + "秒）");
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("pnpm install 成功");
                return InstallResult.success(output);
            }

            log.error("pnpm install 失败，exit code: {}", exitCode);
            return InstallResult.failed(output, "exit code: " + exitCode);
        } catch (Exception e) {
            log.error("pnpm install 异常: {}", e.getMessage(), e);
            return InstallResult.failed("", e.getMessage());
        }
    }

    /**
     * 构建 pnpm install 命令
     */
    private List<String> buildPnpmCommand(boolean force) {
        String cmd = isWindows() ? "pnpm.cmd" : "pnpm";
        List<String> parts = new ArrayList<>(List.of(cmd, "install"));
        if (force) {
            parts.add("--force");
        }
        return parts;
    }

    /**
     * 检查 node_modules 是否完整
     */
    public boolean isNodeModulesComplete(File projectDir) {
        File nodeModules = new File(projectDir, "node_modules");
        if (!nodeModules.exists() || !nodeModules.isDirectory()) {
            return false;
        }

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

        // 检查 .pnpm 目录非空
        String[] files = pnpmDir.list();
        if (files == null || files.length == 0) {
            return false;
        }

        if (!isViteRuntimeResolvable(projectDir)) {
            return false;
        }

        // 检查关键原生包完整性
        return areNativePackagesComplete(projectDir);
    }

    /**
     * 校验 Vite 是否能被当前 node_modules 正常加载。
     * <p>
     * 这能捕获仅靠文件存在性无法发现的损坏，例如 vite 的传递依赖 picomatch 缺失。
     */
    private boolean isViteRuntimeResolvable(File projectDir) {
        List<String> cmd = new ArrayList<>(List.of(
                isWindows() ? "node.exe" : "node",
                "--input-type=module",
                "--eval",
                "import('vite').then(() => process.exit(0)).catch((error) => { console.error(error?.message || error); process.exit(1); })"
        ));

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectDir);
            pb.redirectErrorStream(true);
            pb.environment().put("NO_UPDATE_NOTIFIER", "1");
            pb.environment().put("NPM_CONFIG_AUDIT", "false");
            pb.environment().put("NPM_CONFIG_FUND", "false");

            Process process = pb.start();
            String output = readProcessOutput(process);
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                log.warn("vite 运行时校验超时: {}", projectDir.getAbsolutePath());
                return false;
            }

            int exitCode = process.exitValue();
            if (exitCode != 0) {
                log.warn("vite 运行时校验失败，exit code: {}, output: {}", exitCode, output.trim());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("vite 运行时校验异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查关键原生包是否完整（有 package.json）
     * <p>
     * pnpm --force 会删除并重建包，有时只恢复了 .node 二进制文件但丢失了 package.json，
     * 导致 Node.js 无法 resolve 模块。
     */
    private boolean areNativePackagesComplete(File projectDir) {
        if (!isWindows()) {
            return true; // 非 Windows 平台不需要检查
        }

        File pnpmDir = new File(projectDir, "node_modules/.pnpm");
        if (!pnpmDir.exists()) {
            return true;
        }

        for (String[] nativePkg : CRITICAL_NATIVE_PACKAGES) {
            if (!isNativePackageValid(pnpmDir, nativePkg[0], nativePkg[1])) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检查单个原生包是否有效
     *
     * @param pnpmDir     .pnpm 目录
     * @param dirPrefix   包目录前缀（如 "@rollup+rollup-win32"）
     * @param modulePath  模块路径（如 "@rollup/rollup-win32-x64-msvc"）
     * @return 是否有效
     */
    private boolean isNativePackageValid(File pnpmDir, String dirPrefix, String modulePath) {
        String[] entries = pnpmDir.list((dir, name) -> name.startsWith(dirPrefix));
        if (entries == null || entries.length == 0) {
            return true; // 没找到目录，可能非 Windows 或版本不同，不阻塞
        }

        for (String entry : entries) {
            File pkgDir = new File(pnpmDir, entry + "/node_modules/" + modulePath);
            if (pkgDir.exists() && pkgDir.isDirectory()) {
                File pkgJson = new File(pkgDir, "package.json");
                if (!pkgJson.exists()) {
                    log.warn("原生包 {} 缺少 package.json: {}", modulePath, pkgDir.getAbsolutePath());
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 清理损坏的原生包目录
     * <p>
     * 删除缺少 package.json 的原生包目录，让 pnpm 重新安装。
     */
    private void cleanCorruptedNativePackages(File projectDir) {
        File pnpmDir = new File(projectDir, "node_modules/.pnpm");
        if (!pnpmDir.exists()) {
            return;
        }

        for (String[] nativePkg : CRITICAL_NATIVE_PACKAGES) {
            String dirPrefix = nativePkg[0];
            String modulePath = nativePkg[1];

            String[] entries = pnpmDir.list((dir, name) -> name.startsWith(dirPrefix));
            if (entries == null) continue;

            for (String entry : entries) {
                File pkgDir = new File(pnpmDir, entry + "/node_modules/" + modulePath);
                if (pkgDir.exists() && pkgDir.isDirectory()) {
                    File pkgJson = new File(pkgDir, "package.json");
                    if (!pkgJson.exists()) {
                        log.warn("清理损坏的原生包目录: {}", pkgDir.getAbsolutePath());
                        deleteRecursive(pkgDir);
                    }
                }
            }
        }
    }

    /**
     * 杀掉可能锁定 node_modules 的进程（vite、esbuild）
     * 仅在 Windows 上使用。
     */
    private void killLockingProcesses(File projectDir) {
        try {
            log.info("释放 node_modules 文件锁: {}", projectDir.getAbsolutePath());
            String[] targets = {"esbuild.exe"};
            for (String target : targets) {
                try {
                    ProcessBuilder pb = new ProcessBuilder("cmd", "/c", "taskkill /F /IM " + target + "/T");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    p.waitFor(10, TimeUnit.SECONDS);
                    log.info("taskkill {} 输出: {}", target, output.trim());
                } catch (Exception e) {
                    log.debug("taskkill {} 失败: {}", target, e.getMessage());
                }
            }
            // 杀掉当前项目的 vite 进程
            try {
                String projectPath = projectDir.getAbsolutePath().replace("\\", "\\\\");
                ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                        "wmic process where \"CommandLine like '%vite%' and CommandLine like '%" + projectPath + "%'\" call terminate");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                p.waitFor(10, TimeUnit.SECONDS);
                log.info("wmic vite terminate 输出: {}", output.trim());
            } catch (Exception e) {
                log.debug("wmic vite terminate 失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("释放文件锁失败: {}", e.getMessage());
        }
    }

    /**
     * 检查输出是否包含 EPERM 错误
     */
    private boolean isPermissionError(String output) {
        if (output == null) return false;
        String upper = output.toUpperCase(Locale.ROOT);
        return upper.contains("EPERM") || upper.contains("OPERATION NOT PERMITTED");
    }

    /**
     * 读取进程输出（截断到安全长度）
     */
    private String readProcessOutput(Process process) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        process.getInputStream().transferTo(buffer);
        String output = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        return output.length() > 12000 ? output.substring(output.length() - 12000) : output;
    }

    /**
     * 递归删除目录
     */
    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        file.delete();
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("windows");
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
