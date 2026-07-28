package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.recipe.CreateRecipeRenderer;
import com.rush.rushaicodemother.orchestration.create.recipe.RecipeRenderResult;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 将确定性 CREATE 渲染路由到拥有所选模板的模块。
 *
 * <p>此服务有意仅包含注册表验证和调度。模板特定
 * 配方构建和源渲染属于独立的渲染器模块。</p>
 */
@Service
public class CreateRecipeRendererService {

    private final Map<String, CreateRecipeRenderer> renderersByTemplate;
    private final TemplateVariableEngine variableEngine;

    public CreateRecipeRendererService(List<CreateRecipeRenderer> renderers,
                                       TemplateVariableEngine variableEngine) {
        this.renderersByTemplate = indexRenderers(renderers);
        this.variableEngine = Objects.requireNonNull(variableEngine, "variableEngine must not be null");
    }

    public boolean supportsTemplate(String templateId) {
        return templateId != null && renderersByTemplate.containsKey(templateId);
    }

    /**
 * 渲染创建{@code Recipe}渲染器。
 *
 * @param userMessage 用户消息
 * @param group 分组
 * @param spec {@code spec} 对应的调用参数
 * @return 创建{@code Recipe}渲染器
 */
    public RecipeRenderResult render(String userMessage, SlotGroup group, CreateSpec spec) {
        if (group == null || spec == null) {
            return RecipeRenderResult.empty();
        }
        CreateRecipeRenderer renderer = renderersByTemplate.get(group.templateId());
        if (renderer == null) {
            return RecipeRenderResult.empty();
        }
        TemplateVariableManifest manifest = variableEngine.manifest(group.templateId(), spec);
        RecipeRenderResult result = renderer.render(userMessage, group, spec, manifest);
        if (result == null) {
            throw new IllegalStateException("CREATE recipe renderer returned null: " + renderer.templateId());
        }
        return result;
    }

    /** 返回索引{@code Renderers}。 */
    private Map<String, CreateRecipeRenderer> indexRenderers(List<CreateRecipeRenderer> renderers) {
        if (renderers == null || renderers.isEmpty()) {
            throw new IllegalArgumentException("At least one CREATE recipe renderer must be configured");
        }
        Map<String, CreateRecipeRenderer> indexed = new LinkedHashMap<>();
        for (CreateRecipeRenderer renderer : renderers) {
            Objects.requireNonNull(renderer, "CREATE recipe renderer must not be null");
            String templateId = renderer.templateId();
            if (templateId == null || templateId.isBlank()) {
                throw new IllegalArgumentException("CREATE recipe renderer templateId must not be blank");
            }
            CreateRecipeRenderer previous = indexed.putIfAbsent(templateId, renderer);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate CREATE recipe renderer for template: " + templateId);
            }
        }
        return Collections.unmodifiableMap(indexed);
    }
}
