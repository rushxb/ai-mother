package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.ContextSummaryArtifact;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.context.AiContextBoundaryService;
import com.rush.rushaicodemother.orchestration.context.repository.ProtectedRepositoryContextEnvelope;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextPurpose;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextRequest;
import com.rush.rushaicodemother.orchestration.context.repository.RepositoryContextTrustService;
import com.rush.rushaicodemother.orchestration.context.repository.RetrievedRepositoryEvidence;
import com.rush.rushaicodemother.orchestration.decision.GenerationGuidanceSelection;
import com.rush.rushaicodemother.memory.GenerationWorkingMemoryService;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.dag.GenerationNodeReplayPolicy;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Context：提取项目上下文。
 */
@Component
public class ContextAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;
    private final RepositoryContextTrustService repositoryContextTrustService;
    private final GenerationWorkingMemoryService workingMemoryService;

    public ContextAgentNode(GenerationAgentSupport support) {
        this(
                support,
                new RepositoryContextTrustService(new AiContextBoundaryService()),
                null
        );
    }

    @Autowired
    public ContextAgentNode(GenerationAgentSupport support,
                            RepositoryContextTrustService repositoryContextTrustService,
                            GenerationWorkingMemoryService workingMemoryService) {
        super(
                "context",
                "Context",
                "context",
                List.of("template"),
                GenerationNodeReplayPolicy.REPLAY_SAFE,
                Set.of(ContextSummaryArtifact.KEY)
        );
        this.support = support;
        this.repositoryContextTrustService = repositoryContextTrustService;
        this.workingMemoryService = workingMemoryService;
    }

    /**
 * 执行上下文智能体节点处理流程。
 *
 * @param context 执行上下文
 * @return 上下文智能体节点
 */
    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        GenerationGuidanceSelection guidanceSelection = context.getRequest()
                .scenarioDecision()
                .guidanceSelection();
        CodeGenTypeEnum targetType = context.getTargetType() == null
                ? context.getRequest().currentType()
                : context.getTargetType();
        GenerationAgentSupport.ProjectContextPackage contextPackage = new GenerationAgentSupport.ProjectContextPackage(
                "general",
                List.of(),
                0,
                0,
                List.of(),
                context.getRequest().hasGeneratedCode() ? "empty" : "new_project",
                "",
                List.of()
        );
        if (app != null && app.getId() != null) {
            File rootDir = support.resolveWorkspaceRoot(app, targetType);
            if (rootDir != null) {
                contextPackage = support.buildProjectContextPackage(
                        app,
                        targetType,
                        context.getRequest().userMessage(),
                        rootDir,
                        context.getWorkspaceIndexSnapshot(),
                        guidanceSelection.contextFileHints()
                );
            }
        }
        ProtectedRepositoryContextEnvelope protectedContext = repositoryContextTrustService.protect(
                RepositoryContextRequest.forPurpose(
                        RepositoryContextPurpose.HEAVY,
                        context.getRequest().userMessage()
                ),
                new RetrievedRepositoryEvidence(
                        contextPackage.projectContext(),
                        contextPackage.projectFiles().stream()
                                .map(file -> new RetrievedRepositoryEvidence.FileEvidence(
                                        file.relativePath(), file.content(), file.truncated()))
                                .toList()
                )
        );
        if (workingMemoryService != null) {
            workingMemoryService.recordContextDigest(
                    context.getTask().getTaskId(), protectedContext.workspaceVersion());
        }
        List<String> normalizedSelectedFiles = support.normalizeSelectedFiles(contextPackage.selectedFiles());
        GenerationArtifact artifact = ContextSummaryArtifact.create(
                new ContextSummaryArtifact.RepositoryContext(
                        contextPackage.intent(),
                        normalizedSelectedFiles,
                        contextPackage.indexedFileCount(),
                        contextPackage.indexedSymbolCount(),
                        contextPackage.indexHits(),
                        contextPackage.contextMode(),
                        protectedContext.content()
                ),
                new ContextSummaryArtifact.ContextProtection(
                        protectedContext.workspaceVersion(),
                        protectedContext.redacted(),
                        protectedContext.truncated(),
                        protectedContext.sourceChars()
                ),
                new ContextSummaryArtifact.AgentGuidance(
                        StrUtil.blankToDefault(context.getRequest().resolveMemoryContext(), ""),
                        context.getRequest().hasGeneratedCode(),
                        guidanceSelection.recipes(),
                        guidanceSelection.skills()
                )
        ).toArtifact();
        String summary = StrUtil.isBlank(contextPackage.projectContext())
                ? "未发现可复用项目上下文，将按新项目处理"
                : "已提取意图化精简上下文";
        return AgentNodeResult.of(
                summary,
                List.of(artifact),
                Map.ofEntries(
                        Map.entry("indexedFileCount", contextPackage.indexedFileCount()),
                        Map.entry("indexedSymbolCount", contextPackage.indexedSymbolCount()),
                        Map.entry("indexHitCount", contextPackage.indexHits().size()),
                        Map.entry("selectedFileCount", normalizedSelectedFiles.size()),
                        Map.entry("contextMode", contextPackage.contextMode()),
                        Map.entry("skillCount", guidanceSelection.skills().size()),
                        Map.entry("contextSourceCount", protectedContext.sources().size()),
                        Map.entry("contextPromptInjectionRisk", protectedContext.promptInjectionRisk().name()),
                        Map.entry("contextTokenBudget", protectedContext.tokenBudget()),
                        Map.entry("contextEstimatedTokens", protectedContext.estimatedTokens()),
                        Map.entry("contextOutboundAllowed", protectedContext.outboundAllowed())
                )
        );
    }
}
