package com.rush.rushaicodemother.orchestration.edit;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextPurpose;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextRequest;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextTrustService;
import com.rush.rushaicodemother.orchestration.context.repository.RetrievedRepositoryEvidence;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import com.rush.rushaicodemother.service.devserver.DevServerManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 定位编辑文件并组装有界项目上下文以进行轻量级编辑。 */
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
    private final RepositoryContextTrustService repositoryContextTrustService;

    /**
 * 汇总相关数据并组装轻量编辑上下文{@code Assembler}。
 *
 * @param workspace 工作区
 * @param userMessage 用户消息
 * @return 轻量编辑上下文{@code Assembler}
 */
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
            return new LightweightEditContext(candidates, "", false, null);
        }
        String rawProjectContext = buildRawProjectContext(
                contextPackage, workspace.appId(), userMessage);
        ProtectedRepositoryContextEnvelope contextEnvelope = repositoryContextTrustService.protect(
                RepositoryContextRequest.forPurpose(
                        RepositoryContextPurpose.LIGHT_EDIT, userMessage),
                RetrievedRepositoryEvidence.fromFileContents(
                        rawProjectContext, contextPackage.fileContents())
        );
        return new LightweightEditContext(
                candidates, contextEnvelope.content(), true, contextEnvelope);
    }

    /**
 * 返回{@code rebuild}执行后校验失败。
 *
 * @param workspace 工作区
 * @param userMessage 用户消息
 * @param validationResult 校验结果
 * @param fallbackContext 回退上下文
 * @return 处理后的轻量编辑上下文{@code Assembler}文本
 */
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

    /** 构建并返回项目上下文。 */
    private String buildRawProjectContext(EditContextPackage contextPackage,
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

    /** 追加候选{@code Reasons}。 */
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

    /** 构建并返回{@code Recent}开发服务器输出。 */
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

    /** 判断{@code Useful}{@code Diagnostic}{@code Line}是否满足约束。 */
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
