package com.yupi.yuaicodemother.ai.tools;

import cn.hutool.core.util.StrUtil;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * npm 命令执行辅助类
 */
final class NpmCommandSupport {

    private static final int MAX_LOG_CHARS = 12000;

    private NpmCommandSupport() {
    }

    static CommandResult runCommand(Path workingDir, int timeoutSeconds, String... commandParts) {
        String commandText = String.join(" ", commandParts);
        try {
            Process process = new ProcessBuilder(commandParts)
                    .directory(workingDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            String output = readOutput(process.getInputStream());
            if (!finished) {
                process.destroyForcibly();
                return new CommandResult(false, commandText, null, true, output, "命令执行超时（" + timeoutSeconds + "秒）");
            }
            return new CommandResult(process.exitValue() == 0, commandText, process.exitValue(), false, output, null);
        } catch (Exception e) {
            return new CommandResult(false, commandText, null, false, "", e.getMessage());
        }
    }

    static String npmCommand() {
        return isWindows() ? "npm.cmd" : "npm";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("windows");
    }

    private static String readOutput(InputStream inputStream) throws Exception {
        String output = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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
