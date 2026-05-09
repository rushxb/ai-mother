package com.yupi.yuaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 构建 Vue 项目
 */
@Slf4j
@Component
public class VueProjectBuilder {

    private static final int MAX_LOG_CHARS = 12000;

    /**
     * 异步构建 Vue 项目
     *
     * @param projectPath
     */
    public void buildProjectAsync(String projectPath) {
        Thread.ofVirtual().name("vue-builder-" + System.currentTimeMillis())
                .start(() -> {
                    try {
                        buildProject(projectPath);
                    } catch (Exception e) {
                        log.error("异步构建 Vue 项目时发生异常: {}", e.getMessage(), e);
                    }
                });
    }

    /**
     * 构建 Vue 项目
     *
     * @param projectPath 项目根目录路径
     * @return 是否构建成功
     */
    public boolean buildProject(String projectPath) {
        return buildProjectWithResult(projectPath).success();
    }

    /**
     * 构建 Vue 项目并返回详细结果
     *
     * @param projectPath 项目根目录路径
     * @return 详细构建结果
     */
    public BuildResult buildProjectWithResult(String projectPath) {
        File projectDir = new File(projectPath);
        if (!projectDir.exists() || !projectDir.isDirectory()) {
            log.error("项目目录不存在：{}", projectPath);
            return BuildResult.invalid(projectPath, "项目目录不存在");
        }
        // 检查是否有 package.json 文件
        File packageJsonFile = new File(projectDir, "package.json");
        if (!packageJsonFile.exists()) {
            log.error("项目目录中没有 package.json 文件：{}", projectPath);
            return BuildResult.invalid(projectPath, "项目目录中没有 package.json 文件");
        }
        log.info("开始构建 Vue 项目：{}", projectPath);
        // 执行 npm install
        CommandResult installResult = executeNpmInstall(projectDir);
        if (!installResult.success()) {
            log.error("npm install 执行失败：{}", projectPath);
            return BuildResult.installFailed(projectPath, installResult);
        }
        // 执行 npm run build
        CommandResult buildResult = executeNpmBuild(projectDir);
        if (!buildResult.success()) {
            log.error("npm run build 执行失败：{}", projectPath);
            return BuildResult.buildFailed(projectPath, installResult, buildResult);
        }
        // 验证 dist 目录是否生成
        File distDir = new File(projectDir, "dist");
        if (!distDir.exists() || !distDir.isDirectory()) {
            log.error("构建完成但 dist 目录未生成：{}", projectPath);
            return BuildResult.distMissing(projectPath, installResult, buildResult);
        }
        log.info("Vue 项目构建成功，dist 目录：{}", projectPath);
        return BuildResult.success(projectPath, installResult, buildResult);
    }

    /**
     * 执行 npm install 命令
     */
    private CommandResult executeNpmInstall(File projectDir) {
        log.info("执行 npm install...");
        return executeCommand(projectDir, 300, buildCommand("npm"), "install", "--no-audit", "--no-fund"); // 5分钟超时
    }

    /**
     * 执行 npm run build 命令
     */
    private CommandResult executeNpmBuild(File projectDir) {
        log.info("执行 npm run build...");
        return executeCommand(projectDir, 180, buildCommand("npm"), "run", "build"); // 3分钟超时
    }

    /**
     * 根据操作系统构造命令
     *
     * @param baseCommand
     * @return
     */
    private String buildCommand(String baseCommand) {
        if (isWindows()) {
            return baseCommand + ".cmd";
        }
        return baseCommand;
    }

    /**
     * 操作系统检测
     *
     * @return
     */
    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    /**
     * 执行命令
     *
     * @param workingDir     工作目录
     * @param command        命令字符串
     * @param timeoutSeconds 超时时间（秒）
     * @return 是否执行成功
     */
    private CommandResult executeCommand(File workingDir, int timeoutSeconds, String... commandParts) {
        String command = String.join(" ", commandParts);
        try {
            log.info("在目录 {} 中执行命令: {}", workingDir.getAbsolutePath(), command);
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts)
                    .directory(workingDir)
                    .redirectErrorStream(true);
            processBuilder.environment().put("NO_UPDATE_NOTIFIER", "1");
            processBuilder.environment().put("NPM_CONFIG_AUDIT", "false");
            processBuilder.environment().put("NPM_CONFIG_FUND", "false");
            Process process = processBuilder.start();
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputReader = Thread.startVirtualThread(() -> {
                try {
                    process.getInputStream().transferTo(outputBuffer);
                } catch (Exception e) {
                    log.warn("读取命令输出失败: {}", command, e);
                }
            });
            // 等待进程完成，设置超时
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.error("命令执行超时（{}秒），强制终止进程", timeoutSeconds);
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outputReader.join(TimeUnit.SECONDS.toMillis(5));
                String output = readProcessOutput(outputBuffer);
                return CommandResult.timeout(command, timeoutSeconds, output);
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            String output = readProcessOutput(outputBuffer);
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("命令执行成功: {}", command);
                return CommandResult.success(command, exitCode, output);
            } else {
                log.error("命令执行失败，退出码: {}", exitCode);
                return CommandResult.failed(command, exitCode, output);
            }
        } catch (Exception e) {
            log.error("执行命令失败: {}, 错误信息: {}", Arrays.toString(commandParts), e.getMessage());
            return CommandResult.exception(command, e.getMessage());
        }
    }

    private String readProcessOutput(ByteArrayOutputStream outputBuffer) {
        String output = new String(outputBuffer.toByteArray(), StandardCharsets.UTF_8);
        return truncateLog(output);
    }

    private String truncateLog(String content) {
        if (StrUtil.isBlank(content)) {
            return "";
        }
        String normalized = content.replace("\0", "");
        if (normalized.length() <= MAX_LOG_CHARS) {
            return normalized;
        }
        return normalized.substring(normalized.length() - MAX_LOG_CHARS);
    }

    public record CommandResult(String command, boolean success, Integer exitCode, boolean timeout, String output,
                                String errorMessage) {

        private static CommandResult success(String command, int exitCode, String output) {
            return new CommandResult(command, true, exitCode, false, output, null);
        }

        private static CommandResult failed(String command, int exitCode, String output) {
            return new CommandResult(command, false, exitCode, false, output, null);
        }

        private static CommandResult timeout(String command, int timeoutSeconds, String output) {
            return new CommandResult(command, false, null, true, output, "命令执行超时（" + timeoutSeconds + "秒）");
        }

        private static CommandResult exception(String command, String errorMessage) {
            return new CommandResult(command, false, null, false, "", errorMessage);
        }

        public String toDiagnosticBlock() {
            StringBuilder builder = new StringBuilder();
            builder.append("命令: ").append(command).append('\n');
            builder.append("结果: ").append(success ? "成功" : "失败").append('\n');
            if (exitCode != null) {
                builder.append("退出码: ").append(exitCode).append('\n');
            }
            if (timeout) {
                builder.append("超时: 是").append('\n');
            }
            if (StrUtil.isNotBlank(errorMessage)) {
                builder.append("异常: ").append(errorMessage).append('\n');
            }
            builder.append("日志:\n");
            if (StrUtil.isBlank(output)) {
                builder.append("(无输出)");
            } else {
                builder.append(output.trim());
            }
            return builder.toString();
        }
    }

    public record BuildResult(boolean success, String stage, String projectPath, String summary,
                              CommandResult installResult, CommandResult buildResult) {

        private static BuildResult invalid(String projectPath, String summary) {
            return new BuildResult(false, "prepare", projectPath, summary, null, null);
        }

        private static BuildResult installFailed(String projectPath, CommandResult installResult) {
            return new BuildResult(false, "install", projectPath, "npm install 失败", installResult, null);
        }

        private static BuildResult buildFailed(String projectPath, CommandResult installResult,
                                               CommandResult buildResult) {
            return new BuildResult(false, "build", projectPath, "npm run build 失败", installResult, buildResult);
        }

        private static BuildResult distMissing(String projectPath, CommandResult installResult,
                                               CommandResult buildResult) {
            return new BuildResult(false, "dist", projectPath, "构建完成但未生成 dist 目录", installResult, buildResult);
        }

        private static BuildResult success(String projectPath, CommandResult installResult,
                                           CommandResult buildResult) {
            return new BuildResult(true, "done", projectPath, "Vue 项目构建成功", installResult, buildResult);
        }

        public String toDiagnosticReport() {
            StringBuilder builder = new StringBuilder();
            builder.append("项目路径: ").append(projectPath).append('\n');
            builder.append("构建结果: ").append(success ? "成功" : "失败").append('\n');
            builder.append("失败阶段: ").append(stage).append('\n');
            builder.append("摘要: ").append(summary).append('\n');
            if (installResult != null) {
                builder.append("\n[安装阶段]\n")
                        .append(installResult.toDiagnosticBlock())
                        .append('\n');
            }
            if (buildResult != null) {
                builder.append("\n[构建阶段]\n")
                        .append(buildResult.toDiagnosticBlock())
                        .append('\n');
            }
            return builder.toString().trim();
        }

        public String toFailureSummary() {
            List<String> parts = List.of(
                    "Vue 项目构建失败",
                    "阶段: " + stage,
                    "摘要: " + summary
            );
            StringBuilder builder = new StringBuilder(String.join("，", parts));
            if (buildResult != null) {
                builder.append("。构建日志片段：").append(extractSingleLine(buildResult.output()));
            } else if (installResult != null) {
                builder.append("。安装日志片段：").append(extractSingleLine(installResult.output()));
            }
            return builder.toString();
        }

        private String extractSingleLine(String output) {
            if (StrUtil.isBlank(output)) {
                return "无";
            }
            String normalized = output.replace("\r", " ").replace("\n", " ").trim();
            return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 300));
        }
    }

}
