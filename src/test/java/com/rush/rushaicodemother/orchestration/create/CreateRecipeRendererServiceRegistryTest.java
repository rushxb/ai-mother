package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;
import com.rush.rushaicodemother.orchestration.create.recipe.CreateRecipeRenderer;
import com.rush.rushaicodemother.orchestration.create.recipe.RecipeRenderResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CreateRecipeRendererServiceRegistryTest {

    @Test
    void shouldRejectMissingRendererConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new CreateRecipeRendererService(List.of(), new TemplateVariableEngine()));
    }

    @Test
    void shouldRejectDuplicateTemplateOwnership() {
        CreateRecipeRenderer first = new StubRenderer("vue-web-basic");
        CreateRecipeRenderer duplicate = new StubRenderer("vue-web-basic");

        assertThrows(IllegalArgumentException.class,
                () -> new CreateRecipeRendererService(List.of(first, duplicate), new TemplateVariableEngine()));
    }

    @Test
    void shouldReturnEmptyResultForInvalidOrUnsupportedInput() {
        CreateRecipeRendererService service = new CreateRecipeRendererService(
                List.of(new StubRenderer("vue-web-basic")), new TemplateVariableEngine());

        assertFalse(service.render("message", null, null).available());
        assertFalse(service.render(
                "message",
                new SlotGroup("x", "unknown", "x", List.of(), 0),
                spec()
        ).available());
    }

    private CreateSpec spec() {
        return new CreateSpec(
                new CreateSpec.Product("web", "demo", "Demo", "user", "goal"),
                List.of(),
                List.of(),
                new CreateSpec.Frontend(
                        "basic",
                        List.of(),
                        "comfortable",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        new CreateSpec.Theme("#2563eb", "#f97316", "#f8fafc", "8px", "light")
                ),
                new CreateSpec.Backend(
                        "rest",
                        false,
                        true,
                        true,
                        false,
                        false,
                        List.of(),
                        false,
                        false,
                        List.of(),
                        "standard_json",
                        "record"
                ),
                new CreateSpec.Database(List.of(), List.of(), false, "append_sql_schema"),
                new CreateSpec.Content("professional", "demo", List.of(), List.of(), null),
                new CreateSpec.Constraints(true, List.of(), List.of(), 1, 1)
        );
    }

    private record StubRenderer(String templateId) implements CreateRecipeRenderer {
        @Override
        public RecipeRenderResult render(String userMessage,
                                         SlotGroup group,
                                         CreateSpec spec,
                                         TemplateVariableManifest manifest) {
            return RecipeRenderResult.empty();
        }
    }
}
