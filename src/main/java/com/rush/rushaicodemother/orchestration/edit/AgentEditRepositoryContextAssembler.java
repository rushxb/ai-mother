package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextPurpose;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextRequest;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextTrustService;
import com.rush.rushaicodemother.orchestration.context.repository.RetrievedRepositoryEvidence;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Objects;

/** 将 AGENT_EDIT 的读取事实组装为唯一允许入模的受保护上下文。 */
@Service
@RequiredArgsConstructor
public class AgentEditRepositoryContextAssembler {

    private final RepositoryContextTrustService repositoryContextTrustService;

    public ProtectedRepositoryContextEnvelope assemble(
            AgentEditReadResult readResult,
            AgentEditUnderstanding understanding,
            String userMessage) {
        Objects.requireNonNull(readResult, "AGENT_EDIT 读取结果不能为空");
        Objects.requireNonNull(understanding, "AGENT_EDIT 理解结果不能为空");
        EditContextPackage contextPackage = Objects.requireNonNull(
                readResult.contextPackage(), "AGENT_EDIT 项目上下文包不能为空");

        String rawContext = buildRawContext(readResult, understanding, contextPackage);
        return repositoryContextTrustService.protect(
                RepositoryContextRequest.forPurpose(
                        RepositoryContextPurpose.AGENT_EDIT, userMessage),
                RetrievedRepositoryEvidence.fromFileContents(
                        rawContext, contextPackage.fileContents())
        );
    }

    private String buildRawContext(AgentEditReadResult readResult,
                                   AgentEditUnderstanding understanding,
                                   EditContextPackage contextPackage) {
        StringBuilder builder = new StringBuilder();
        builder.append("AGENT_EDIT Read 结果:\n");
        builder.append("- intent: ").append(readResult.intent()).append('\n');
        builder.append("- riskLevel: ").append(readResult.riskLevel()).append('\n');
        builder.append("- selectedFiles: ").append(readResult.selectedFiles()).append("\n\n");
        builder.append("Code Graph:\n");
        builder.append("- importRelations: ").append(readResult.importRelations()).append('\n');
        builder.append("- referencedBy: ").append(readResult.referencedBy()).append('\n');
        builder.append("- symbols: ").append(readResult.symbols()).append('\n');
        builder.append("- diagnostics: ").append(readResult.graphDiagnostics()).append("\n\n");
        builder.append("AGENT_EDIT Understand 结果:\n");
        builder.append(understanding.structureSummary()).append("\n\n");
        if (StrUtil.isNotBlank(contextPackage.projectIndex())) {
            builder.append(contextPackage.projectIndex()).append("\n\n");
        }
        Map<String, String> fileContents = contextPackage.fileContents();
        if (fileContents != null) {
            fileContents.forEach((relativePath, content) -> builder
                    .append("文件: ").append(relativePath).append('\n')
                    .append("```\n")
                    .append(StrUtil.blankToDefault(content, ""))
                    .append("\n```\n\n"));
        }
        return builder.toString();
    }
}
