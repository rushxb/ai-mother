package com.rush.rushaicodemother.orchestration.readonly;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 可持久化、可直接展示的只读分析结果。 */
public record ReadOnlyAnalysisResult(
        String summary,
        List<Finding> findings,
        List<FileReference> references,
        String noChangeJustification
) {

    private static final String EMPTY_ANALYSIS_SUMMARY = "分析已完成";

    public ReadOnlyAnalysisResult {
        summary = textOrDefault(summary, EMPTY_ANALYSIS_SUMMARY);
        findings = findings == null
                ? List.of()
                : findings.stream().filter(java.util.Objects::nonNull).toList();
        references = references == null
                ? List.of()
                : references.stream().filter(java.util.Objects::nonNull).toList();
        noChangeJustification = textOrDefault(
                noChangeJustification, "本次请求为只读分析，因此未修改工作区");
    }

    /** 使用经过工作区上下文校验的引用创建结果副本。 */
    public ReadOnlyAnalysisResult withReferences(List<FileReference> groundedReferences) {
        return new ReadOnlyAnalysisResult(
                summary, findings, groundedReferences, noChangeJustification);
    }

    /**
     * 判断结果是否足以证明已经回答用户的只读意图。
     *
     * <p>文件引用只能证明模型看过哪些上下文，不能替代分析结论。模型返回空结构时，
     * 构造器仍保留可展示的兜底文案，但该文案不得被完成门禁误当作有效分析。</p>
     */
    public boolean provesIntentCoverage() {
        return !EMPTY_ANALYSIS_SUMMARY.equals(summary) || !findings.isEmpty();
    }

    /** 校验只读结果具备真实分析内容，并返回自身便于调用链继续处理。 */
    public ReadOnlyAnalysisResult requireIntentCoverage() {
        if (!provesIntentCoverage()) {
            throw new IllegalStateException("只读分析未返回有效结论或发现");
        }
        return this;
    }

    /** 渲染面向用户的 Markdown 分析报告。 */
    public String renderMarkdown() {
        StringBuilder report = new StringBuilder("## 分析结论\n\n")
                .append(summary);
        if (!findings.isEmpty()) {
            report.append("\n\n## 发现\n");
            for (Finding finding : findings) {
                report.append("\n- [")
                        .append(finding.severity())
                        .append("] ")
                        .append(finding.title())
                        .append("：")
                        .append(finding.description());
            }
        }
        if (!references.isEmpty()) {
            report.append("\n\n## 文件依据\n");
            for (FileReference reference : references) {
                report.append("\n- `")
                        .append(reference.relativePath());
                if (reference.line() != null) {
                    report.append(':').append(reference.line());
                }
                report.append("`：").append(reference.reason());
            }
        }
        return report.append("\n\n## 未改动说明\n\n")
                .append(noChangeJustification)
                .toString();
    }

    /** 返回供生成产物持久化的结构化载荷。 */
    public Map<String, Object> toPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("summary", summary);
        payload.put("findings", findings.stream().map(Finding::toPayload).toList());
        payload.put("references", references.stream().map(FileReference::toPayload).toList());
        payload.put("workspacePublished", false);
        payload.put("toolWriteCount", 0);
        payload.put("buildCount", 0);
        return Map.copyOf(payload);
    }

    /** 单项分析发现。 */
    public record Finding(String title, String severity, String description) {

        public Finding {
            title = textOrDefault(title, "未命名发现");
            severity = normalizeSeverity(severity);
            description = textOrDefault(description, "未提供详情");
        }

        private Map<String, Object> toPayload() {
            return Map.of("title", title, "severity", severity, "description", description);
        }
    }

    /** 文件级事实引用；行号可缺省。 */
    public record FileReference(String relativePath, Integer line, String reason) {

        public FileReference {
            relativePath = textOrDefault(relativePath, "unknown").replace('\\', '/');
            line = line == null || line <= 0 ? null : line;
            reason = textOrDefault(reason, "分析上下文依据");
        }

        private Map<String, Object> toPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("relativePath", relativePath);
            if (line != null) {
                payload.put("line", line);
            }
            payload.put("reason", reason);
            return Map.copyOf(payload);
        }
    }

    private static String normalizeSeverity(String value) {
        String normalized = textOrDefault(value, "INFO").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL" -> normalized;
            default -> "INFO";
        };
    }

    private static String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
