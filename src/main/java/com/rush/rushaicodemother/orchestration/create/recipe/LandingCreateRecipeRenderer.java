package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.LandingSlotFallbackRenderer;
import com.rush.rushaicodemother.orchestration.create.SlotGroup;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableManifest;
import org.springframework.stereotype.Component;

import java.util.Objects;

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
