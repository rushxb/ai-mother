package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

/**
 * 移动端创建配方渲染器。
 */
@Component
final class MobileCreateRecipeRenderer extends AbstractSlotRecipeRenderer<BasicRecipe> {

    private final BasicRecipeFactory recipeFactory;
    private final MobileVueRecipeTemplates templates;

    MobileCreateRecipeRenderer(BasicRecipeFactory recipeFactory, MobileVueRecipeTemplates templates) {
        super("vue-web-mobile", "AI spec + 本地 mobile recipe 已生成移动端应用骨架");
        this.recipeFactory = recipeFactory;
        this.templates = templates;
    }

    @Override
    protected BasicRecipe createRecipe(String userMessage, CreateSpec spec) {
        return recipeFactory.create(userMessage, spec);
    }

    /**
 * 渲染插槽。
 *
 * @param slotId 插槽编号
 * @param recipe {@code recipe} 对应的调用参数
 * @return 插槽
 */
    @Override
    protected PatchOperation renderSlot(String slotId, BasicRecipe recipe) {
        return switch (slotId) {
            case "home_content" -> PatchOperation.modify("src/views/HomeView.vue", templates.mobileHomeView());
            case "mock_data" -> PatchOperation.modify("src/data/mock.ts", templates.mobileMockData(recipe));
            case "tabbar_config" -> PatchOperation.modify("src/data/tabbar.ts", templates.mobileTabbar());
            case "product_list" -> PatchOperation.modify("src/data/products.ts", templates.mobileProducts(recipe));
            case "theme_tokens" -> PatchOperation.modify("src/styles/mobile.css", templates.mobileThemeCss(recipe));
            default -> null;
        };
    }
}
