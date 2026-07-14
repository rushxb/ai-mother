package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;

/**
 * Extension point for one deterministic CREATE template recipe.
 */
public interface CreateRecipeRenderer {

    String templateId();

    RecipeRenderResult render(String userMessage,
                              SlotGroup group,
                              CreateSpec spec,
                              TemplateVariableManifest manifest);
}
