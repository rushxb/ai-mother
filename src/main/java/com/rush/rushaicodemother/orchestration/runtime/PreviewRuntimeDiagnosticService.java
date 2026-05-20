package com.rush.rushaicodemother.orchestration.runtime;

import com.rush.rushaicodemother.orchestration.artifact.RuntimeDiagnosticResult;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class PreviewRuntimeDiagnosticService {

    public RuntimeDiagnosticResult analyze(String url, String report) {
        String normalizedReport = report == null ? "" : report;
        String lower = normalizedReport.toLowerCase(Locale.ROOT);
        List<String> findings = new ArrayList<>();
        List<String> consoleErrors = new ArrayList<>();
        boolean whiteScreen = lower.contains("疑似白屏")
                || lower.contains("没有渲染出可见内容")
                || lower.contains("正文长度: 0");
        if (whiteScreen) {
            findings.add("运行时诊断发现白屏或无可见内容");
        }
        if (lower.contains("severe") || lower.contains("runtime error") || lower.contains("syntaxerror")) {
            consoleErrors.add("浏览器控制台存在严重运行时错误");
            findings.add("运行时诊断发现控制台错误");
        }
        if (lower.contains("failed to load resource") || lower.contains("404")) {
            findings.add("运行时诊断发现资源加载失败或 404");
        }
        String status = findings.isEmpty() ? "passed" : "failed";
        String summary = findings.isEmpty() ? "运行时诊断通过" : "构建通过，但运行时诊断失败";
        return new RuntimeDiagnosticResult(
                status,
                url,
                List.of("/"),
                findings,
                consoleErrors,
                whiteScreen,
                summary,
                normalizedReport,
                LocalDateTime.now()
        );
    }

    public RuntimeDiagnosticResult skipped(String reason) {
        return new RuntimeDiagnosticResult(
                "skipped",
                "",
                List.of(),
                List.of(),
                List.of(),
                false,
                reason,
                reason,
                LocalDateTime.now()
        );
    }
}
