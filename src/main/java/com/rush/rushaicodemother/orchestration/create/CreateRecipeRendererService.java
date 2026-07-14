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
 * Routes deterministic CREATE rendering to the module that owns the selected template.
 *
 * <p>This service intentionally contains only registry validation and dispatch. Template-specific
 * recipe construction and source rendering belong to independent renderer modules.</p>
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
