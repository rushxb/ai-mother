package com.rush.rushaicodemother.core.builder;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.infrastructure.diagnostic.PublicDiagnosticSanitizer;
import com.rush.rushaicodemother.infrastructure.process.ProjectCommandResult;

/** 集中维护 Go 构建诊断的公开脱敏格式。 */
final class GoBuildDiagnosticFormatter {

    private GoBuildDiagnosticFormatter() {
    }

    static String publicProjectPath(GoBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(result.projectPath(), 500);
    }

    static String publicSummary(GoBuildResult result) {
        return PublicDiagnosticSanitizer.sanitizeSingleLine(result.summary(), 600);
    }

    static String toPublicDiagnosticReport(GoBuildResult result) {
        StringBuilder report = new StringBuilder()
                .append("项目路径: ").append(result.projectPath()).append('\n')
                .append("验证结果: ").append(result.success() ? "通过" : "失败").append('\n')
                .append("验证阶段: ").append(result.stage()).append('\n')
                .append("摘要: ").append(result.summary());
        appendCommandResult(report, result.testResult());
        return PublicDiagnosticSanitizer.sanitizeForPublicOutput(report.toString());
    }

    /** 将当前对象转换为公开失败汇总。 */
    static String toPublicFailureSummary(GoBuildResult result) {
        StringBuilder summary = new StringBuilder("Go 项目构建测试失败")
                .append("，阶段: ").append(result.stage())
                .append("，摘要: ").append(result.summary());
        ProjectCommandResult commandResult = result.testResult();
        if (commandResult != null) {
            if (commandResult.exitCode() != null) {
                summary.append("，退出码: ").append(commandResult.exitCode());
            }
            String diagnostic = StrUtil.blankToDefault(commandResult.output(), commandResult.errorDetail());
            if (StrUtil.isNotBlank(diagnostic)) {
                summary.append("。诊断: ").append(singleLine(diagnostic));
            }
        }
        return PublicDiagnosticSanitizer.sanitizeSingleLine(summary.toString(), 1_200);
    }

    /** 追加命令结果。 */
    private static void appendCommandResult(StringBuilder report, ProjectCommandResult result) {
        if (result == null) {
            return;
        }
        report.append("\n\n[Go 测试]\n")
                .append("命令: ").append(result.command()).append('\n')
                .append("状态: ").append(result.status().name()).append('\n');
        if (result.exitCode() != null) {
            report.append("退出码: ").append(result.exitCode()).append('\n');
        }
        if (StrUtil.isNotBlank(result.errorDetail())) {
            report.append("异常: ").append(result.errorDetail()).append('\n');
        }
        report.append("日志:\n")
                .append(StrUtil.blankToDefault(result.output(), "(无输出)"));
    }

    private static String singleLine(String value) {
        String normalized = value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return StrUtil.sub(normalized, 0, Math.min(normalized.length(), 600));
    }
}
