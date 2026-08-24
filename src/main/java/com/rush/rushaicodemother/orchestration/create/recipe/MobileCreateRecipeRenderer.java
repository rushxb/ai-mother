package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 移动端创建配方渲染器。
 *
 * <p>移动首页、底部导航、活动入口和商品列表共同消费 {@code mobileData.ts}。
 * renderer 在一次渲染中只生成一个共享数据补丁，再将该补丁能够证明的能力标记为已覆盖，
 * 避免同一路径的多个整文件写入，也避免“recipe 写了文件但浏览器没有读取”的假成功。</p>
 */
@Component
final class MobileCreateRecipeRenderer implements CreateRecipeRenderer {

    private static final String TEMPLATE_ID = "vue-web-mobile";
    private static final String SUMMARY = "AI spec + 本地 mobile recipe 已生成可见的移动端运行时数据";
    private static final String RUNTIME_DATA_SLOT = "mock_data";
    private static final Set<String> RUNTIME_DATA_CAPABILITIES = Set.of(
            RUNTIME_DATA_SLOT,
            "tabbar_config",
            "mobile_campaigns",
            "product_list"
    );

    private final BasicRecipeFactory recipeFactory;
    private final MobileVueRecipeTemplates templates;

    MobileCreateRecipeRenderer(BasicRecipeFactory recipeFactory, MobileVueRecipeTemplates templates) {
        this.recipeFactory = recipeFactory;
        this.templates = templates;
    }

    @Override
    public String templateId() {
        return TEMPLATE_ID;
    }

    @Override
    public RecipeRenderResult render(String userMessage,
                                     SlotGroup group,
                                     CreateSpec spec,
                                     TemplateVariableManifest manifest) {
        if (group == null || spec == null || !TEMPLATE_ID.equals(group.templateId())) {
            return RecipeRenderResult.empty();
        }

        List<String> requestedSlots = group.slotIds() == null ? List.of() : group.slotIds();
        Set<String> requestedSlotSet = new LinkedHashSet<>(requestedSlots);
        BasicRecipe recipe = recipeFactory.create(userMessage, spec);
        List<String> filledSlots = new ArrayList<>();
        List<PatchOperation> operations = new ArrayList<>();

        if (requestedSlotSet.contains("home_content")) {
            filledSlots.add("home_content");
            operations.add(PatchOperation.modify("src/views/HomeView.vue", templates.mobileHomeView()));
        }

        boolean writesRuntimeData = requestedSlotSet.contains(RUNTIME_DATA_SLOT);
        if (writesRuntimeData) {
            operations.add(PatchOperation.modify("src/data/mobileData.ts", templates.mobileRuntimeData(recipe)));
        }

        for (String slotId : requestedSlotSet) {
            if (RUNTIME_DATA_CAPABILITIES.contains(slotId) && writesRuntimeData) {
                filledSlots.add(slotId);
            }
        }

        if (requestedSlotSet.contains("theme_tokens")) {
            filledSlots.add("theme_tokens");
            operations.add(PatchOperation.modify("src/styles/mobile.css", templates.mobileThemeCss(recipe)));
        }

        return RecipeRenderResult.of(
                requestedSlots,
                filledSlots,
                operations,
                SUMMARY,
                manifest
        );
    }
}
