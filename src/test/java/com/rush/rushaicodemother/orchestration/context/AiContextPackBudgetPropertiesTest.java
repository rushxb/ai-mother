package com.rush.rushaicodemother.orchestration.context;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class AiContextPackBudgetPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AiContextPackBudgetProperties.class);

    @Test
    void hardcodedContextBudgetMustMatchProductionConstants() {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();

        assertTrue(properties.isConfigurationValid());
        assertEquals(AiContextPackBudgetProperties.GENERATION_MAX_TOKENS,
                properties.maxTokens("vue_project"));
        assertEquals(AiContextPackBudgetProperties.REPAIR_MAX_TOKENS,
                properties.maxTokens("repair"));
        assertEquals(AiContextPackBudgetProperties.TOKENIZER_MODEL,
                properties.getTokenizerModel());
        assertEquals(AiContextPackBudgetProperties.TOKEN_SAFETY_MARGIN,
                properties.getTokenSafetyMargin());
    }

    /** 上下文预算已下沉为硬编码常量，外部配置不得改写。 */
    @Test
    void externalPropertiesMustNotOverrideHardcodedContextBudget() {
        contextRunner
                .withPropertyValues(
                        "app.ai-context-pack.generation-max-tokens=9000",
                        "app.ai-context-pack.repair-max-tokens=9000",
                        "app.ai-context-pack.tokenizer-model=gpt-3.5-turbo",
                        "app.ai-context-pack.token-safety-margin=1.95",
                        "app.ai-context-pack.max-section-tokens=4000"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AiContextPackBudgetProperties properties =
                            context.getBean(AiContextPackBudgetProperties.class);
                    assertThat(properties.getGenerationMaxTokens())
                            .isEqualTo(AiContextPackBudgetProperties.GENERATION_MAX_TOKENS);
                    assertThat(properties.getRepairMaxTokens())
                            .isEqualTo(AiContextPackBudgetProperties.REPAIR_MAX_TOKENS);
                    assertThat(properties.getTokenizerModel())
                            .isEqualTo(AiContextPackBudgetProperties.TOKENIZER_MODEL);
                    assertThat(properties.getTokenSafetyMargin())
                            .isEqualTo(AiContextPackBudgetProperties.TOKEN_SAFETY_MARGIN);
                    assertThat(properties.getMaxSectionTokens())
                            .isEqualTo(AiContextPackBudgetProperties.MAX_SECTION_TOKENS);
                });
    }

    @Test
    void directValidationRejectsUnsafeTokenizerMargins() {
        AiContextPackBudgetProperties properties = new AiContextPackBudgetProperties();

        properties.setTokenSafetyMargin(0.99);
        assertFalse(properties.isConfigurationValid());

        properties.setTokenSafetyMargin(2.01);
        assertFalse(properties.isConfigurationValid());

        properties.setTokenSafetyMargin(1.25);
        assertTrue(properties.isConfigurationValid());
    }
}
