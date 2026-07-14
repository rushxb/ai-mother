package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

@Component
final class BasicCreateRecipeRenderer extends AbstractSlotRecipeRenderer<BasicRecipe> {

    private final BasicRecipeFactory recipeFactory;
    private final BasicVueRecipeTemplates templates;

    BasicCreateRecipeRenderer(BasicRecipeFactory recipeFactory, BasicVueRecipeTemplates templates) {
        super("vue-web-basic", "AI spec + 本地 basic recipe 已生成通用应用骨架");
        this.recipeFactory = recipeFactory;
        this.templates = templates;
    }

    @Override
    protected BasicRecipe createRecipe(String userMessage, CreateSpec spec) {
        return recipeFactory.create(userMessage, spec);
    }

    @Override
    protected PatchOperation renderSlot(String slotId, BasicRecipe recipe) {
        return switch (slotId) {
            case "home_content" -> PatchOperation.modify("src/views/HomeView.vue", templates.basicHomeView());
            case "mock_data" -> PatchOperation.modify("src/data/siteData.ts", templates.basicSiteData(recipe));
            case "app_config" -> PatchOperation.modify("src/data/app.config.ts", templates.basicAppConfig(recipe));
            case "navigation_items" -> PatchOperation.modify("src/data/navigation.ts", templates.basicNavigation());
            case "theme_tokens" -> PatchOperation.modify("src/styles/theme.css", templates.basicThemeCss(recipe));
            default -> null;
        };
    }
}
