package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.AiCreateSpecServiceFactory;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class CreateSpecServiceSpringContextTest {

    @Test
    void shouldInstantiateCreateServicesWithSpringConstructorInjection() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AiCreateSpecServiceFactory.class, () -> mock(AiCreateSpecServiceFactory.class));
            context.register(
                    CreateRecipeRendererService.class,
                    CreateSpecNormalizer.class,
                    CreateSpecService.class,
                    LandingSlotFallbackRenderer.class,
                    TemplateVariableEngine.class
            );

            context.refresh();

            assertNotNull(context.getBean(CreateSpecService.class));
            assertNotNull(context.getBean(CreateRecipeRendererService.class));
        }
    }
}
