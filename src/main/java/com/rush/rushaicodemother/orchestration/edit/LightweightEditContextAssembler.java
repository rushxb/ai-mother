package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Locates edit files and assembles bounded project context for lightweight edits. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LightweightEditContextAssembler {

    private static final int MAX_RECENT_DEV_SERVER_LINES = 60;
    private static final int MAX_USEFUL_DEV_SERVER_LINES = 30;

    private final EditFileLocatorService editFileLocatorService;
    private final EditContextPackageBuilder editContextPackageBuilder;
    private final EditValidationPolicyService editValidationPolicyService;
    private final DevServerManager devServerManager;

    public LightweightEditContext assemble(GenerationWorkspace workspace, String userMessage) {
        if (workspace == null) {
            return LightweightEditContext.noCandidates();
        }
        List<EditFileCandidate> candidates = editFileLocatorService.locate(
                workspace, userMessage, workspace.codeGenType());
        if (candidates == null || candidates.isEmpty()) {
            return LightweightEditContext.noCandidates();
        }
        EditContextPackage contextPackage = editContextPackageBuilder.build(workspace, candidates);
        if (contextPackage == null || contextPackage.isEmpty()) {
            return new LightweightEditContext(candidates, "", false);
        }
        return new LightweightEditContext(
                candidates,
                buildProjectContext(contextPackage, workspace.appId(), userMessage),
                true
        );
    }

    public String rebuildAfterValidationFailure(GenerationWorkspace workspace,
                                                String userMessage,
                                                BackgroundValidationService.ValidationResult validationResult,
                                                String fallbackContext) {
        try {
            String validationMessage = validationResult == null
                    ? ""
                    : StrUtil.blankToDefault(validationResult.message(), "");
            LightweightEditContext retryContext = assemble(
                    workspace,
                    StrUtil.blankToDefault(userMessage, "")
                            + "\n\n修复后验证失败信息:\n"
                            + validationMessage
            );
            if (retryContext.contextAvailable()) {
                return retryContext.projectContext();
            }
        } catch (Exception exception) {
            log.debug("Failed to rebuild lightweight edit retry context: {}", LogExceptionSanitizer.sanitizeMessage(exception));
        }
        return StrUtil.blankToDefault(fallbackContext, "");
    }

    private String buildProjectContext(EditContextPackage contextPackage,
                                       Long appId,
                                       String userMessage) {
        StringBuilder builder = new StringBuilder();
        String recentDevServerOutput = buildRecentDevServerOutput(appId, userMessage);
        if (StrUtil.isNotBlank(recentDevServerOutput)) {
            builder.append(recentDevServerOutput).append("\n\n");
        }
        if (StrUtil.isNotBlank(contextPackage.projectIndex())) {
            builder.append(contextPackage.projectIndex()).append("\n\n");
        }
        if (contextPackage.candidates() != null && !contextPackage.candidates().isEmpty()) {
            appendCandidateReasons(builder, contextPackage.candidates());
        }
        Map<String, String> fileContents = contextPackage.fileContents();
        if (fileContents != null) {
            fileContents.forEach((relativePath, content) -> builder
                    .append("文件: ").append(relativePath).append('\n')
                    .append("```\n").append(StrUtil.blankToDefault(content, "")).append("\n```\n\n"));
        }
        return builder.toString();
    }

    private void appendCandidateReasons(StringBuilder builder,
                                        List<EditFileCandidate> candidates) {
        builder.append("候选文件定位依据:\n");
        for (EditFileCandidate candidate : candidates) {
            builder.append("- ")
                    .append(candidate.relativePath())
                    .append(" [")
                    .append(candidate.matchType())
                    .append(", score=")
                    .append(candidate.score())
                    .append("]: ")
                    .append(StrUtil.blankToDefault(candidate.reason(), ""));
            if (candidate.matchedTerms() != null && !candidate.matchedTerms().isEmpty()) {
                builder.append("，命中: ").append(candidate.matchedTerms());
            }
            builder.append('\n');
        }
        builder.append('\n');
    }

    private String buildRecentDevServerOutput(Long appId, String userMessage) {
        if (appId == null || !editValidationPolicyService.isRuntimeErrorRepairRequest(userMessage)) {
            return "";
        }
        List<String> lines = devServerManager.getRecentOutputLines(appId, MAX_RECENT_DEV_SERVER_LINES);
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        List<String> usefulLines = lines.stream()
                .filter(this::isUsefulDiagnosticLine)
                .limit(MAX_USEFUL_DEV_SERVER_LINES)
                .toList();
        if (usefulLines.isEmpty()) {
            return "";
        }
        return "最近 Dev Server 输出（用于复现和定位用户报错）:\n" + String.join("\n", usefulLines);
    }

    private boolean isUsefulDiagnosticLine(String line) {
        if (line == null) {
            return false;
        }
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.contains("error")
                || lower.contains("warn")
                || lower.contains("syntaxerror")
                || lower.contains("referenceerror")
                || lower.contains("typeerror")
                || lower.contains("failed to resolve")
                || lower.contains("hmr update")
                || lower.contains("[vite]");
    }
}
