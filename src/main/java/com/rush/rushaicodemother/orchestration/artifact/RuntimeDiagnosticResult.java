package com.rush.rushaicodemother.orchestration.artifact;

import cn.hutool.core.util.StrUtil;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行时Diagnostic执行结果。
 */
public record RuntimeDiagnosticResult(
        String status,
        String url,
        List<String> routesChecked,
        List<String> findings,
        List<String> consoleErrors,
        boolean whiteScreenDetected,
        String summary,
        String report,
        LocalDateTime createdAt
) {

    public RuntimeDiagnosticResult {
        status = StrUtil.blankToDefault(status, "skipped");
        routesChecked = routesChecked == null ? List.of() : List.copyOf(routesChecked);
        findings = findings == null ? List.of() : List.copyOf(findings);
        consoleErrors = consoleErrors == null ? List.of() : List.copyOf(consoleErrors);
        summary = StrUtil.blankToDefault(summary, "运行时诊断未执行");
        report = StrUtil.blankToDefault(report, summary);
        createdAt = createdAt == null ? LocalDateTime.now() : createdAt;
    }

    public boolean passed() {
        return "passed".equals(status) || "skipped".equals(status);
    }

    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("url", url);
        payload.put("routesChecked", routesChecked);
        payload.put("findings", findings);
        payload.put("consoleErrors", consoleErrors);
        payload.put("whiteScreenDetected", whiteScreenDetected);
        payload.put("summary", summary);
        payload.put("report", report);
        payload.put("createdAt", createdAt.toString());
        return payload;
    }
}
