package com.rush.rushaicodemother.orchestration.create;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CreateTemplatePlannerSpringContextTest {

    @Test
    void productionCreatePlanningAdaptersAreRegisteredBySpring() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(
                    CreateGenerationPlanAssembler.class,
                    VueTemplateFeaturePlanner.class,
                    VueCreateTemplatePlanningAdapter.class,
                    BackendCreateTemplatePlanningAdapter.class,
                    FullStackCreateTemplatePlanningAdapter.class,
                    CreateTemplatePlanner.class
            );

            context.refresh();

            assertNotNull(context.getBean(CreateTemplatePlanner.class));
            assertEquals(3, context.getBeansOfType(CreateTemplatePlanningAdapter.class).size());
        }
    }
}
