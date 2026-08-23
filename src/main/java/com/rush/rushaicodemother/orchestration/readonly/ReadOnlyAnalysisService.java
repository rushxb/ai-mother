package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.AgentEditContextCollector;
import com.rush.rushaicodemother.orchestration.edit.AgentEditReadResult;
import com.rush.rushaicodemother.orchestration.edit.EditContextPackage;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 只读分析深模块：负责采集有界上下文、调用一次模型并校验所有文件引用。
 *
 * <p>模型端口不携带文件系统或工具对象，输出引用又必须命中已采集文件，
 * 因而模型既不能写工作区，也不能把臆造路径作为分析证据发布。</p>
 */
@Service
public class ReadOnlyAnalysisService {

    private final AgentEditContextCollector contextCollector;
    private final ReadOnlyAnalysisModel analysisModel;

    public ReadOnlyAnalysisService(AgentEditContextCollector contextCollector,
                                   ReadOnlyAnalysisModel analysisModel) {
        this.contextCollector = Objects.requireNonNull(contextCollector, "只读上下文采集器不能为空");
        this.analysisModel = Objects.requireNonNull(analysisModel, "只读分析模型不能为空");
    }

    /** 执行一次有事实依据的只读分析。 */
    public ReadOnlyAnalysisResult analyze(String taskId,
                                          IntentOperationType operationType,
                                          String userPrompt,
                                          GenerationWorkspace workspace,
                                          CodeGenTypeEnum codeGenType) {
        requireReadOnlyOperation(operationType);
        AgentEditReadResult context = contextCollector.collect(workspace, userPrompt, codeGenType);
        Map<String, Integer> allowedReferenceLineCounts = collectReferenceLineCounts(context);
        if (allowedReferenceLineCounts.isEmpty()) {
            // 无项目事实时禁止调用模型：泛化结论既无法完成引用校验，也不应产生 provider 成本。
            throw new IllegalStateException("只读分析未采集到可引用的项目文件");
        }
        List<String> allowedReferences = List.copyOf(allowedReferenceLineCounts.keySet());
        ReadOnlyAnalysisRequest request = new ReadOnlyAnalysisRequest(
                operationType,
                userPrompt,
                renderProjectContext(context),
                allowedReferences
        );
        ReadOnlyAnalysisResult rawResult = analysisModel.analyze(taskId, request);
        if (rawResult == null) {
            throw new IllegalStateException("只读分析模型未返回结果");
        }
        rawResult.requireIntentCoverage();
        return rawResult.withReferences(groundReferences(
                rawResult.references(), allowedReferenceLineCounts));
    }

    private List<ReadOnlyAnalysisResult.FileReference> groundReferences(
            List<ReadOnlyAnalysisResult.FileReference> references,
            Map<String, Integer> allowedReferenceLineCounts) {
        List<ReadOnlyAnalysisResult.FileReference> grounded = new ArrayList<>();
        Set<ReferenceLocation> seen = new LinkedHashSet<>();
        for (ReadOnlyAnalysisResult.FileReference reference : references) {
            String normalizedPath = normalizePath(reference.relativePath());
            Integer lineCount = allowedReferenceLineCounts.get(normalizedPath);
            // 路径仍有事实依据时，越界行号降级为文件级引用，避免向用户发布伪造位置。
            Integer groundedLine = lineCount == null
                    ? null
                    : groundedLine(reference.line(), lineCount);
            ReferenceLocation location = new ReferenceLocation(normalizedPath, groundedLine);
            if (lineCount != null && seen.add(location)) {
                grounded.add(new ReadOnlyAnalysisResult.FileReference(
                        normalizedPath,
                        groundedLine,
                        reference.reason()));
            }
        }
        if (grounded.isEmpty() && !allowedReferenceLineCounts.isEmpty()) {
            // 已采集文件不等于模型实际使用了该文件；不得用任意首文件伪造分析依据。
            throw new IllegalStateException("只读分析未返回有效的项目文件依据");
        }
        return List.copyOf(grounded);
    }

    private Map<String, Integer> collectReferenceLineCounts(AgentEditReadResult context) {
        if (context == null) {
            return Map.of();
        }
        Map<String, String> normalizedContents = new LinkedHashMap<>();
        if (context.contextPackage() != null && context.contextPackage().fileContents() != null) {
            context.contextPackage().fileContents().forEach((path, content) -> {
                String normalizedPath = normalizePath(path);
                if (isSafeRelativePath(normalizedPath)) {
                    normalizedContents.put(normalizedPath, content);
                }
            });
        }

        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        List<String> selectedFiles = context.selectedFiles() == null
                ? List.of()
                : context.selectedFiles();
        for (String selectedFile : selectedFiles) {
            String normalizedPath = normalizePath(selectedFile);
            if (isSafeRelativePath(normalizedPath)) {
                String content = normalizedContents.get(normalizedPath);
                lineCounts.putIfAbsent(normalizedPath, content == null
                        ? 0
                        : Math.toIntExact(content.lines().count()));
            }
        }
        return java.util.Collections.unmodifiableMap(lineCounts);
    }

    private Integer groundedLine(Integer requestedLine, int collectedLineCount) {
        return requestedLine != null && requestedLine <= collectedLineCount
                ? requestedLine
                : null;
    }

    private String renderProjectContext(AgentEditReadResult context) {
        if (context == null || context.contextPackage() == null) {
            return "未采集到可引用文件；只能说明上下文不足，不得编造事实。";
        }
        EditContextPackage contextPackage = context.contextPackage();
        StringBuilder rendered = new StringBuilder("项目索引：\n")
                .append(textOrDefault(contextPackage.projectIndex(), "无"))
                .append("\n\n已授权引用文件：\n")
                .append(String.join("\n", context.selectedFiles()))
                .append("\n\n文件内容：\n");
        Map<String, String> fileContents = contextPackage.fileContents() == null
                ? Map.of()
                : contextPackage.fileContents();
        for (Map.Entry<String, String> entry : fileContents.entrySet()) {
            rendered.append("\n--- ").append(entry.getKey()).append(" ---\n")
                    .append(entry.getValue());
        }
        return rendered.toString();
    }

    private void requireReadOnlyOperation(IntentOperationType operationType) {
        if (operationType != IntentOperationType.EXPLAIN
                && operationType != IntentOperationType.AUDIT
                && operationType != IntentOperationType.PLAN) {
            throw new IllegalArgumentException("只读分析仅接受 EXPLAIN、AUDIT 或 PLAN 操作");
        }
    }

    private String normalizePath(String value) {
        String normalized = value == null ? "" : value.trim().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }

    private boolean isSafeRelativePath(String value) {
        if (value == null || value.isBlank() || value.startsWith("/") || value.contains(":")) {
            return false;
        }
        for (String segment : value.split("/")) {
            if (segment.equals("..") || segment.indexOf('\0') >= 0) {
                return false;
            }
        }
        return true;
    }

    private String textOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private record ReferenceLocation(String relativePath, Integer line) {
    }
}
