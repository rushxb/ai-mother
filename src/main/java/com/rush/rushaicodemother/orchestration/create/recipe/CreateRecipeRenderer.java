package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;

/**
 * 一个确定性 CREATE 模板配方的扩展点。
 */
public interface CreateRecipeRenderer {

    String templateId();

    RecipeRenderResult render(String userMessage,
                              SlotGroup group,
                              CreateSpec spec,
                              TemplateVariableManifest manifest);
}
