package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.entity.App;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.GenerationArtifact;
import com.yupi.yuaicodemother.orchestration.dag.AgentNodeResult;
import com.yupi.yuaicodemother.orchestration.dag.GenerationAgentContext;
import com.yupi.yuaicodemother.orchestration.recipe.GenerationRecipe;
import org.springframework.stereotype.Component;

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

    public ContextAgentNode(GenerationAgentSupport support) {
        super("context", "Context", "context", List.of());
        this.support = support;
    }

    @Override
    public AgentNodeResult execute(GenerationAgentContext context) {
        App app = context.getRequest().app();
        CodeGenTypeEnum currentType = context.getRequest().currentType();
        GenerationAgentSupport.ProjectContextPackage contextPackage = new GenerationAgentSupport.ProjectContextPackage(
                "general",
                List.of(),
                0,
                0,
                List.of(),
                context.getRequest().hasGeneratedCode() ? "empty" : "new_project",
                ""
        );
        if (app != null && app.getId() != null && context.getRequest().hasGeneratedCode()) {
            File rootDir = support.resolveWorkspaceRoot(app);
            if (rootDir != null) {
                contextPackage = support.buildProjectContextPackage(
                        app,
                        currentType,
                        context.getRequest().userMessage(),
                        rootDir
                );
            }
        }
        List<String> normalizedSelectedFiles = support.normalizeSelectedFiles(contextPackage.selectedFiles());
        List<GenerationRecipe> matchedRecipes = support.matchRecipes(
                context.getRequest().userMessage(),
                contextPackage.projectContext()
        );
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intent", contextPackage.intent());
        payload.put("selectedFiles", normalizedSelectedFiles);
        payload.put("indexedFileCount", contextPackage.indexedFileCount());
        payload.put("indexedSymbolCount", contextPackage.indexedSymbolCount());
        payload.put("indexHits", contextPackage.indexHits());
        payload.put("contextMode", contextPackage.contextMode());
        payload.put("projectContext", StrUtil.blankToDefault(contextPackage.projectContext(), ""));
        payload.put("hasGeneratedCode", context.getRequest().hasGeneratedCode());
        payload.put("recipeIds", matchedRecipes.stream().map(GenerationRecipe::id).toList());
        payload.put("recipes", support.buildRecipePayloads(matchedRecipes));
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
                        "contextMode", contextPackage.contextMode()
                )
        );
    }
}
