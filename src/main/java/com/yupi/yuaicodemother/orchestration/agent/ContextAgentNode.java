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

import static com.yupi.yuaicodemother.constant.AppConstant.CODE_OUTPUT_ROOT_DIR;

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
                context.getRequest().hasGeneratedCode() ? "empty" : "new_project",
                ""
        );
        if (app != null && app.getId() != null && context.getRequest().hasGeneratedCode()) {
            File rootDir = new File(CODE_OUTPUT_ROOT_DIR + File.separator + app.getCodeGenType() + "_" + app.getId());
            if (rootDir.exists() && rootDir.isDirectory()) {
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
                        "selectedFileCount", normalizedSelectedFiles.size(),
                        "contextMode", contextPackage.contextMode()
                )
        );
    }
}
