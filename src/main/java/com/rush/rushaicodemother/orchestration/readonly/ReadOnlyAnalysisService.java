package com.rush.rushaicodemother.orchestration.readonly;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.AgentEditContextCollector;
import com.rush.rushaicodemother.orchestration.edit.AgentEditReadResult;
import com.rush.rushaicodemother.orchestration.edit.EditContextPackage;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextPurpose;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextRequest;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextTrustService;
import com.rush.rushaicodemother.orchestration.context.repository.RetrievedRepositoryEvidence;
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
    private final RepositoryContextTrustService repositoryContextTrustService;

    public ReadOnlyAnalysisService(AgentEditContextCollector contextCollector,
                                   ReadOnlyAnalysisModel analysisModel,
                                   RepositoryContextTrustService repositoryContextTrustService) {
        this.contextCollector = Objects.requireNonNull(contextCollector, "只读上下文采集器不能为空");
        this.analysisModel = Objects.requireNonNull(analysisModel, "只读分析模型不能为空");
        this.repositoryContextTrustService = Objects.requireNonNull(
                repositoryContextTrustService, "项目上下文信任服务不能为空");
    }

    /** 执行一次有事实依据的只读分析。 */
    public ReadOnlyAnalysisOutcome analyze(String taskId,
                                           IntentOperationType operationType,
                                           String userPrompt,
                                           GenerationWorkspace workspace,
                                           CodeGenTypeEnum codeGenType) {
        ReadOnlyEvidenceContract contract = ReadOnlyEvidenceContract.resolve(operationType);
        AgentEditReadResult context = contextCollector.collect(workspace, userPrompt, codeGenType);
        Map<String, Integer> allowedReferenceLineCounts = collectReferenceLineCounts(context);
        List<String> allowedReferences = List.copyOf(allowedReferenceLineCounts.keySet());
        ProtectedRepositoryContextEnvelope contextEnvelope = protectContext(
                context, userPrompt, operationType);
        if (allowedReferences.isEmpty() && !contract.modelAllowedWithoutRepository()) {
            return unavailableWithoutRepository(contract, contextEnvelope);
        }
        ReadOnlyAnalysisRequest request = new ReadOnlyAnalysisRequest(
                operationType,
                userPrompt,
                contextEnvelope.content(),
                allowedReferences
        );
        ReadOnlyAnalysisResult rawResult = analysisModel.analyze(taskId, request);
        if (rawResult == null) {
            throw new IllegalStateException("只读分析模型未返回结果");
        }
        rawResult.requireIntentCoverage();
        ReadOnlyAnalysisResult groundedResult = rawResult.withReferences(groundReferences(
                rawResult.references(),
                allowedReferenceLineCounts,
                contract.groundedReferenceRequired()));
        ReadOnlyEvidenceBasis evidenceBasis = allowedReferences.isEmpty()
                ? ReadOnlyEvidenceBasis.USER_REQUIREMENT
                : operationType == IntentOperationType.PLAN
                        ? ReadOnlyEvidenceBasis.REPOSITORY_AND_REQUIREMENT
                        : ReadOnlyEvidenceBasis.REPOSITORY_FACTS;
        return ReadOnlyAnalysisOutcome.completed(
                groundedResult,
                evidenceBasis,
                contextEnvelope
        );
    }

    private List<ReadOnlyAnalysisResult.FileReference> groundReferences(
            List<ReadOnlyAnalysisResult.FileReference> references,
            Map<String, Integer> allowedReferenceLineCounts,
            boolean groundedReferenceRequired) {
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
        if (grounded.isEmpty() && groundedReferenceRequired
                && !allowedReferenceLineCounts.isEmpty()) {
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
            if (!isSafeRelativePath(normalizedPath)
                    || !normalizedContents.containsKey(normalizedPath)) {
                // 候选文件名只用于发现，未实际采集并发送给模型的内容不能升级为分析依据。
                continue;
            }
            String content = normalizedContents.get(normalizedPath);
            if (content == null) {
                continue;
            }
            lineCounts.putIfAbsent(normalizedPath, Math.toIntExact(content.lines().count()));
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

    private ProtectedRepositoryContextEnvelope protectContext(
            AgentEditReadResult context,
            String userPrompt,
            IntentOperationType operationType) {
        EditContextPackage contextPackage = context == null ? null : context.contextPackage();
        Map<String, String> fileContents = contextPackage == null || contextPackage.fileContents() == null
                ? Map.of() : contextPackage.fileContents();
        RetrievedRepositoryEvidence evidence = RetrievedRepositoryEvidence.fromFileContents(
                renderProjectContext(context),
                fileContents
        );
        return repositoryContextTrustService.protect(
                RepositoryContextRequest.forPurpose(
                        RepositoryContextPurpose.READ_ONLY,
                        operationType.name() + ":" + userPrompt
                ),
                evidence
        );
    }

    private ReadOnlyAnalysisOutcome unavailableWithoutRepository(
            ReadOnlyEvidenceContract contract,
            ProtectedRepositoryContextEnvelope contextEnvelope) {
        return switch (contract.emptyRepositoryStatus()) {
            case NO_PROJECT_CONTEXT -> ReadOnlyAnalysisOutcome.unavailable(
                    ReadOnlyAnalysisStatus.NO_PROJECT_CONTEXT,
                    "当前工作区没有可解释的项目文件",
                    "NO_PROJECT_FILES",
                    contextEnvelope
            );
            case NOT_AUDITABLE -> ReadOnlyAnalysisOutcome.unavailable(
                    ReadOnlyAnalysisStatus.NOT_AUDITABLE,
                    "当前工作区没有可审计的项目文件",
                    "NO_AUDITABLE_FILES",
                    contextEnvelope
            );
            case COMPLETED -> throw new IllegalStateException("只读证据合同缺少空项目失败语义");
        };
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
