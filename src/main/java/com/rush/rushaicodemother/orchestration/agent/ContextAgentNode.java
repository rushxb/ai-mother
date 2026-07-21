package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.context.AiContextBoundaryService;
import com.rush.rushaicodemother.memory.GenerationWorkingMemoryService;
import com.rush.rushaicodemother.orchestration.dag.AgentNodeResult;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentContext;
import com.rush.rushaicodemother.orchestration.recipe.GenerationRecipe;
import com.rush.rushaicodemother.orchestration.skill.GenerationSkill;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Context：提取项目上下文。
 */
@Component
public class ContextAgentNode extends BaseGenerationAgentNode {

    private final GenerationAgentSupport support;
    private final AiContextBoundaryService contextBoundaryService;
    private final GenerationWorkingMemoryService workingMemoryService;

    public ContextAgentNode(GenerationAgentSupport support) {
        this(support, new AiContextBoundaryService(), null);
    }

    @Autowired
    public ContextAgentNode(GenerationAgentSupport support,
                            AiContextBoundaryService contextBoundaryService,
                            GenerationWorkingMemoryService workingMemoryService) {
        super("context", "Context", "context", List.of("template"));
        this.support = support;
        this.contextBoundaryService = contextBoundaryService;
        this.workingMemoryService = workingMemoryService;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
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
                ""
        );
        if (app != null && app.getId() != null) {
            File rootDir = support.resolveWorkspaceRoot(app, targetType);
            if (rootDir != null) {
                contextPackage = support.buildProjectContextPackage(
                        app,
                        targetType,
                        context.getRequest().userMessage(),
                        rootDir
                );
            }
        }
        AiContextBoundaryService.ProtectedContext protectedContext =
                contextBoundaryService.protectRepositoryContext(contextPackage.projectContext());
        if (workingMemoryService != null) {
            workingMemoryService.recordContextDigest(
                    context.getTask().getTaskId(), protectedContext.digest());
        }
        List<GenerationSkill> matchedSkills = support.matchSkills(context.getRequest().userMessage());
        List<String> normalizedSelectedFiles = support.normalizeSelectedFiles(contextPackage.selectedFiles());
        List<GenerationRecipe> matchedRecipes = support.matchRecipes(
                context.getRequest().userMessage(),
                protectedContext.content()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", contextPackage.intent());
        payload.put("selectedFiles", normalizedSelectedFiles);
        payload.put("indexedFileCount", contextPackage.indexedFileCount());
        payload.put("indexedSymbolCount", contextPackage.indexedSymbolCount());
        payload.put("indexHits", contextPackage.indexHits());
        payload.put("contextMode", contextPackage.contextMode());
        payload.put("projectContext", protectedContext.content());
        payload.put("contextDigest", protectedContext.digest());
        payload.put("contextTrust", "untrusted_repository_data");
        payload.put("contextSecretsRedacted", protectedContext.redacted());
        payload.put("contextTruncated", protectedContext.truncated());
        payload.put("contextSourceChars", protectedContext.sourceChars());
        payload.put("memoryContext", StrUtil.blankToDefault(context.getRequest().memoryContext(), ""));
        payload.put("hasGeneratedCode", context.getRequest().hasGeneratedCode());
        payload.put("recipeIds", matchedRecipes.stream().map(GenerationRecipe::id).toList());
        payload.put("recipes", support.buildRecipePayloads(matchedRecipes));
        payload.put("skillIds", matchedSkills.stream().map(GenerationSkill::id).toList());
        payload.put("skills", support.buildSkillPayloads(matchedSkills));
        GenerationArtifact artifact = GenerationArtifact.of("context_summary", "Context", "项目上下文", payload);
        String summary = StrUtil.isBlank(contextPackage.projectContext())
                ? "未发现可复用项目上下文，将按新项目处理"
                : "已提取意图化精简上下文";
        return AgentNodeResult.of(
                summary,
                List.of(artifact),
                Map.of(
                        "indexedFileCount", contextPackage.indexedFileCount(),
                        "indexedSymbolCount", contextPackage.indexedSymbolCount(),
                        "indexHitCount", contextPackage.indexHits().size(),
                        "selectedFileCount", normalizedSelectedFiles.size(),
                        "contextMode", contextPackage.contextMode(),
                        "skillCount", matchedSkills.size()
                )
        );
    }
}
