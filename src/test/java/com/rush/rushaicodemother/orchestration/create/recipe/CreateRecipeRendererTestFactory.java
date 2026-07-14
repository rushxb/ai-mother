package com.rush.rushaicodemother.orchestration.create.recipe;

import com.rush.rushaicodemother.orchestration.create.CreateRecipeRendererService;
import com.rush.rushaicodemother.orchestration.create.LandingSlotFallbackRenderer;
import com.rush.rushaicodemother.orchestration.create.TemplateVariableEngine;

import java.util.List;

/**
 * Creates the production renderer graph for focused unit tests without a Spring context.
 */
public final class CreateRecipeRendererTestFactory {

    private CreateRecipeRendererTestFactory() {
    }

    public static CreateRecipeRendererService create() {
        BasicRecipeFactory basicRecipeFactory = new BasicRecipeFactory();
        AdminRecipeFactory adminRecipeFactory = new AdminRecipeFactory();
        BackendRecipeFactory backendRecipeFactory = new BackendRecipeFactory();

        return new CreateRecipeRendererService(List.of(
                new LandingCreateRecipeRenderer(new LandingSlotFallbackRenderer()),
                new BasicCreateRecipeRenderer(basicRecipeFactory, new BasicVueRecipeTemplates()),
                new MobileCreateRecipeRenderer(basicRecipeFactory, new MobileVueRecipeTemplates()),
                new AdminCreateRecipeRenderer(
                        adminRecipeFactory,
                        new AdminDashboardTemplate(),
                        new AdminDataTemplates(),
                        new AdminThemeTemplate()
                ),
                new BackendCreateRecipeRenderer(
                        backendRecipeFactory,
                        new BackendDomainTemplates(),
                        new BackendRepositoryTemplate(),
                        new BackendServiceTemplate(),
                        new BackendHttpTemplates()
                )
        ), new TemplateVariableEngine());
    }
}
