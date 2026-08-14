package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.AgentEditContextCollector;
import com.rush.rushaicodemother.orchestration.edit.AgentEditReadResult;
import com.rush.rushaicodemother.orchestration.edit.EditContextPackage;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        List<String> allowedReferences = context == null ? List.of() : context.selectedFiles();
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
        return rawResult.withReferences(groundReferences(rawResult.references(), allowedReferences));
    }

    private List<ReadOnlyAnalysisResult.FileReference> groundReferences(
            List<ReadOnlyAnalysisResult.FileReference> references,
            List<String> allowedReferences) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String reference : allowedReferences) {
            String normalizedPath = normalizePath(reference);
            if (isSafeRelativePath(normalizedPath)) {
                allowed.add(normalizedPath);
            }
        }
        List<ReadOnlyAnalysisResult.FileReference> grounded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ReadOnlyAnalysisResult.FileReference reference : references) {
            String normalizedPath = normalizePath(reference.relativePath());
            if (allowed.contains(normalizedPath) && seen.add(normalizedPath)) {
                grounded.add(new ReadOnlyAnalysisResult.FileReference(
                        normalizedPath, reference.line(), reference.reason()));
            }
        }
        if (grounded.isEmpty() && !allowed.isEmpty()) {
            grounded.add(new ReadOnlyAnalysisResult.FileReference(
                    allowed.iterator().next(), null, "已采集的项目上下文"));
        }
        return List.copyOf(grounded);
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
}
