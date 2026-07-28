package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 集中维护 Vue 构建诊断的内部格式和公开脱敏格式，避免不同调用边界复制脱敏规则。
 */
final class VueBuildDiagnosticFormatter {

    private VueBuildDiagnosticFormatter() {
    }

    static String publicProjectPath(VueBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(result.projectPath(), 500);
    }

    static String publicSummary(VueBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(result.summary(), 600);
    }

    /** 将当前对象转换为内部{@code Diagnostic}报告。 */
    static String toInternalDiagnosticReport(VueBuildResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("项目路径: ").append(result.projectPath()).append('\n');
        builder.append("构建结果: ").append(result.success() ? "成功" : "失败").append('\n');
        builder.append("失败阶段: ").append(result.stage()).append('\n');
        builder.append("摘要: ").append(result.summary()).append('\n');
        builder.append("验证层级: ").append(result.validationTier()).append('\n');
        builder.append("修复优先级: ").append(result.repairPriority()).append('\n');
        builder.append("执行路径: ").append(result.executionPath()).append('\n');
        if (result.installResult() != null) {
            builder.append("\n[安装阶段]\n")
                    .append(toInternalDiagnosticBlock(result.installResult()))
                    .append('\n');
        }
        if (result.buildResult() != null) {
            builder.append("\n[构建阶段]\n")
                    .append(toInternalDiagnosticBlock(result.buildResult()))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    static String toPublicDiagnosticReport(VueBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(toInternalDiagnosticReport(result));
    }

    /** 将当前对象转换为内部失败汇总。 */
    static String toInternalFailureSummary(VueBuildResult result) {
        List<String> parts = List.of(
                "Vue 项目构建失败",
                "阶段: " + result.stage(),
                "验证层级: " + result.validationTier(),
                "摘要: " + result.summary(),
                "修复优先级: " + result.repairPriority()
        );
        StringBuilder builder = new StringBuilder(String.join("，", parts));
        if (result.buildResult() != null) {
            builder.append("。构建日志片段：").append(extractSingleLine(result.buildResult().output()));
        } else if (result.installResult() != null) {
            builder.append("。安装日志片段：").append(extractSingleLine(result.installResult().output()));
        }
        return builder.toString();
    }

    static String toPublicFailureSummary(VueBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(toInternalFailureSummary(result), 1_200);
    }

    /** 返回命令汇总。 */
    static String commandSummary(VueBuildCommandResult result, String fallback) {
        if (result == null || StrUtil.isBlank(result.command())) {
            return fallback;
        }
        StringBuilder builder = new StringBuilder(result.command()).append(' ').append(fallback);
        if (result.exitCode() != null) {
            builder.append("，exitCode=").append(result.exitCode());
        }
        if (result.timeout()) {
            builder.append("，timeout=true");
        }
        if (StrUtil.isNotBlank(result.errorMessage())) {
            builder.append("，error=").append(result.errorMessage());
        }
        String errorSnippet = extractDiagnosticSnippet(result.output());
        if (StrUtil.isNotBlank(errorSnippet)) {
            builder.append("，日志: ").append(errorSnippet);
        }
        return PublicDiagnosticSanitizer.sanitizeSingleLine(builder.toString(), 600);
    }

    /** 将当前对象转换为内部{@code Diagnostic}{@code Block}。 */
    private static String toInternalDiagnosticBlock(VueBuildCommandResult result) {
        StringBuilder builder = new StringBuilder();
        builder.append("命令: ").append(result.command()).append('\n');
        builder.append("结果: ").append(result.success() ? "成功" : "失败").append('\n');
        if (result.exitCode() != null) {
            builder.append("退出码: ").append(result.exitCode()).append('\n');
        }
        if (result.timeout()) {
            builder.append("超时: 是").append('\n');
        }
        if (StrUtil.isNotBlank(result.errorMessage())) {
            builder.append("异常: ").append(result.errorMessage()).append('\n');
        }
        builder.append("日志:\n");
        builder.append(StrUtil.isBlank(result.output()) ? "(无输出)" : result.output().trim());
        return builder.toString();
    }

    private static String extractSingleLine(String output) {
        if (StrUtil.isBlank(output)) {
            return "无";
        }
        String normalized = output.replace("\r", " ").replace("\n", " ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 300));
    }

    /** 从输入中提取{@code Diagnostic}{@code Snippet}。 */
    private static String extractDiagnosticSnippet(String output) {
        if (StrUtil.isBlank(output)) {
            return "";
        }
        String[] lines = output.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        List<String> usefulLines = Arrays.stream(lines)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .filter(VueBuildDiagnosticFormatter::isUsefulDiagnosticLine)
                .limit(12)
                .toList();
        String snippet = usefulLines.isEmpty()
                ? String.join("\n", Arrays.stream(lines)
                .map(String::trim)
                .filter(StrUtil::isNotBlank)
                .limit(12)
                .toList())
                : String.join("\n", usefulLines);
        String normalized = snippet.replace("\r", " ").replace("\n", " | ").trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 2_000));
    }

    /** 判断{@code Useful}{@code Diagnostic}{@code Line}是否满足约束。 */
    private static boolean isUsefulDiagnosticLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("failed")
                || lower.contains("syntaxerror")
                || lower.contains("referenceerror")
                || lower.contains("typeerror")
                || lower.contains("cannot find")
                || lower.contains("already been declared")
                || lower.contains("is not defined")
                || lower.contains("does not provide an export")
                || lower.contains("failed to resolve")
                || lower.contains(".vue")
                || lower.contains(".js")
                || lower.contains(".ts");
    }
}
