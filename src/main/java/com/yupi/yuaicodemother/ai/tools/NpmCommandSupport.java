package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 前端包管理命令执行辅助类
 */
final class NpmCommandSupport {

    private static final int MAX_LOG_CHARS = 12000;

    private NpmCommandSupport() {
    }

    static CommandResult runCommand(Path workingDir, int timeoutSeconds, String... commandParts) {
        String commandText = String.join(" ", commandParts);
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true);
            processBuilder.environment().put("NO_UPDATE_NOTIFIER", "1");
            processBuilder.environment().put("NPM_CONFIG_AUDIT", "false");
            processBuilder.environment().put("NPM_CONFIG_FUND", "false");
            Process process = processBuilder.start();
            ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            Thread outputReader = Thread.startVirtualThread(() -> {
                try {
                    process.getInputStream().transferTo(outputBuffer);
                } catch (Exception ignored) {
                }
            });
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                outputReader.join(TimeUnit.SECONDS.toMillis(5));
                String output = readOutput(outputBuffer);
                return new CommandResult(false, commandText, null, true, output, "命令执行超时（" + timeoutSeconds + "秒）");
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(5));
            String output = readOutput(outputBuffer);
            return new CommandResult(process.exitValue() == 0, commandText, process.exitValue(), false, output, null);
        } catch (Exception e) {
            return new CommandResult(false, commandText, null, false, "", e.getMessage());
        }
    }

    static String pnpmCommand() {
        return isWindows() ? "pnpm.cmd" : "pnpm";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private static String readOutput(ByteArrayOutputStream outputBuffer) {
        String output = new String(outputBuffer.toByteArray(), StandardCharsets.UTF_8);
        if (StrUtil.isBlank(output)) {
            return "";
        }
        output = output.replace("\0", "");
        if (output.length() <= MAX_LOG_CHARS) {
            return output;
        }
        return output.substring(output.length() - MAX_LOG_CHARS);
    }

    record CommandResult(boolean success, String command, Integer exitCode, boolean timeout, String output,
                         String errorMessage) {

        String toReport() {
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
            builder.append(StrUtil.isBlank(output) ? "(无输出)" : output.trim());
            return builder.toString();
        }

        String toSingleLineSummary() {
            List<String> parts = new java.util.ArrayList<>();
            parts.add(success ? "成功" : "失败");
            if (exitCode != null) {
                parts.add("退出码=" + exitCode);
            }
            if (timeout) {
                parts.add("超时");
            }
            if (StrUtil.isNotBlank(errorMessage)) {
                parts.add("异常=" + errorMessage);
            }
            return String.join("，", parts);
        }
    }
}
