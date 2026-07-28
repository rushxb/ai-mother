package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.LandingSlotFallbackRenderer;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 落地页创建配方渲染器。
 */
@Component
final class LandingCreateRecipeRenderer implements CreateRecipeRenderer {

    private static final String TEMPLATE_ID = "vue-web-landing";

    private final LandingSlotFallbackRenderer landingRenderer;

    LandingCreateRecipeRenderer(LandingSlotFallbackRenderer landingRenderer) {
        this.landingRenderer = Objects.requireNonNull(landingRenderer, "landingRenderer must not be null");
    }

    @Override
    public String templateId() {
        return TEMPLATE_ID;
    }

    /**
 * 渲染{@code Landing}创建{@code Recipe}渲染器。
 *
 * @param userMessage 用户消息
 * @param group 分组
 * @param spec {@code spec} 对应的调用参数
 * @param manifest 清单
 * @return {@code Landing}创建{@code Recipe}渲染器
 */
    @Override
    public RecipeRenderResult render(String userMessage,
                                     SlotGroup group,
                                     CreateSpec spec,
                                     TemplateVariableManifest manifest) {
        if (group == null || spec == null || !TEMPLATE_ID.equals(group.templateId()) || !landingRenderer.supports(group)) {
            return RecipeRenderResult.empty();
        }
        LandingSlotFallbackRenderer.LandingFallback result =
                landingRenderer.renderFromSpec(userMessage, group, spec, "create_spec_recipe");
        return RecipeRenderResult.of(result.filledSlots(), result.patchOperations(), result.summary(), manifest);
    }
}
