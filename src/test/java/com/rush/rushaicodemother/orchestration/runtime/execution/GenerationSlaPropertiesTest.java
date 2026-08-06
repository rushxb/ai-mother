package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class GenerationSlaPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationSlaProperties.class);

    @Test
    void hardcodedRouteProfilesMustMatchProductionConstants() {
        GenerationSlaProperties properties = new GenerationSlaProperties();

        assertTrue(properties.isConfigurationValid());
        assertProfile(properties.profile(GenerationMode.CREATE),
                GenerationSlaProperties.CREATE_NAME,
                GenerationSlaProperties.CREATE_MAX_ROOT_MODEL_ATTEMPTS,
                GenerationSlaProperties.CREATE_MAX_MODEL_TURNS,
                GenerationSlaProperties.CREATE_MAX_PROVIDER_FAILOVER_ATTEMPTS);
        assertProfile(properties.profile(GenerationMode.LIGHT_EDIT),
                GenerationSlaProperties.LIGHT_EDIT_NAME,
                GenerationSlaProperties.LIGHT_EDIT_MAX_ROOT_MODEL_ATTEMPTS,
                GenerationSlaProperties.LIGHT_EDIT_MAX_MODEL_TURNS,
                GenerationSlaProperties.LIGHT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS);
        assertProfile(properties.profile(GenerationMode.AGENT_EDIT),
                GenerationSlaProperties.AGENT_EDIT_NAME,
                GenerationSlaProperties.AGENT_EDIT_MAX_ROOT_MODEL_ATTEMPTS,
                GenerationSlaProperties.AGENT_EDIT_MAX_MODEL_TURNS,
                GenerationSlaProperties.AGENT_EDIT_MAX_PROVIDER_FAILOVER_ATTEMPTS);
        assertProfile(properties.profile(GenerationMode.HEAVY_EXPERT),
                GenerationSlaProperties.HEAVY_EXPERT_NAME,
                GenerationSlaProperties.HEAVY_EXPERT_MAX_ROOT_MODEL_ATTEMPTS,
                GenerationSlaProperties.HEAVY_EXPERT_MAX_MODEL_TURNS,
                GenerationSlaProperties.HEAVY_EXPERT_MAX_PROVIDER_FAILOVER_ATTEMPTS);
        assertProfile(properties.getSaturatedAgentEdit(),
                GenerationSlaProperties.SATURATED_NAME,
                GenerationSlaProperties.SATURATED_MAX_ROOT_MODEL_ATTEMPTS,
                GenerationSlaProperties.SATURATED_MAX_MODEL_TURNS,
                GenerationSlaProperties.SATURATED_MAX_PROVIDER_FAILOVER_ATTEMPTS);
        assertEquals(GenerationSlaProperties.CREATE_FIRST_PREVIEW_COMPLETION_RESERVE,
                properties.profile(GenerationMode.CREATE).getFirstPreviewCompletionReserve());
        assertEquals(GenerationSlaProperties.LIGHT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE,
                properties.profile(GenerationMode.LIGHT_EDIT).getFirstPreviewCompletionReserve());
        assertEquals(GenerationSlaProperties.AGENT_EDIT_FIRST_PREVIEW_COMPLETION_RESERVE,
                properties.profile(GenerationMode.AGENT_EDIT).getFirstPreviewCompletionReserve());
        assertEquals(GenerationSlaProperties.HEAVY_EXPERT_FIRST_PREVIEW_COMPLETION_RESERVE,
                properties.profile(GenerationMode.HEAVY_EXPERT).getFirstPreviewCompletionReserve());
        assertEquals(GenerationSlaProperties.SATURATED_FIRST_PREVIEW_COMPLETION_RESERVE,
                properties.getSaturatedAgentEdit().getFirstPreviewCompletionReserve());
    }

    /** 路由预算固定为常量，历史环境变量与属性名都不得再改写模型调用预算。 */
    @Test
    void externalPropertiesMustNotOverrideHardcodedRouteBudgets() {
        contextRunner
                .withPropertyValues(
                        "app.generation-sla.profiles.create.max-root-model-attempts=5",
                        "app.generation-sla.profiles.create.max-model-turns=23",
                        "app.generation-sla.profiles.create.max-provider-failover-attempts=9",
                        "app.generation-sla.saturated-agent-edit.max-model-turns=16"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationSlaProperties properties = context.getBean(GenerationSlaProperties.class);
                    GenerationSlaProperties.Profile create = properties.profile(GenerationMode.CREATE);
                    assertThat(create.getMaxRootModelAttempts())
                            .isEqualTo(GenerationSlaProperties.CREATE_MAX_ROOT_MODEL_ATTEMPTS);
                    assertThat(create.getMaxModelTurns())
                            .isEqualTo(GenerationSlaProperties.CREATE_MAX_MODEL_TURNS);
                    assertThat(create.getMaxProviderFailoverAttempts())
                            .isEqualTo(GenerationSlaProperties.CREATE_MAX_PROVIDER_FAILOVER_ATTEMPTS);
                    assertThat(properties.getSaturatedAgentEdit().getMaxModelTurns())
                            .isEqualTo(GenerationSlaProperties.SATURATED_MAX_MODEL_TURNS);
                });
    }

    @Test
    void excessiveRouteBudgetIsRejectedBeforeTaskExecution() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        properties.profile(GenerationMode.CREATE).setMaxModelTurns(101);

        assertFalse(properties.isConfigurationValid());
    }

    @Test
    void previewCompletionReserveMustLeaveTheMinimumOptionalOperationWindow() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        GenerationSlaProperties.Profile create = properties.profile(GenerationMode.CREATE);
        create.setFirstPreviewCompletionReserve(Duration.ofMillis(59_501));

        assertFalse(properties.isConfigurationValid());
    }

    @Test
    void createFallbackBudgetMustCoverSpecHeavyRoutingInitialGenerationAndRepairs() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        GenerationSlaProperties.Profile create = properties.profile(GenerationMode.CREATE);
        create.setMaxRootModelAttempts(3);
        create.setMaxRepairRounds(1);

        assertFalse(properties.isConfigurationValid());
    }

    @Test
    void heavyBudgetMustCoverIntentRoutingInitialGenerationAndRepairs() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        GenerationSlaProperties.Profile heavy = properties.profile(GenerationMode.HEAVY_EXPERT);
        heavy.setMaxRootModelAttempts(3);
        heavy.setMaxRepairRounds(2);

        assertFalse(properties.isConfigurationValid());
    }

    @Test
    void editBudgetMustCoverInitialModelCallAndEveryRepair() {
        GenerationSlaProperties properties = new GenerationSlaProperties();
        GenerationSlaProperties.Profile lightEdit = properties.profile(GenerationMode.LIGHT_EDIT);
        lightEdit.setMaxRootModelAttempts(2);
        lightEdit.setMaxRepairRounds(2);

        assertFalse(properties.isConfigurationValid());
    }

    private void assertProfile(GenerationSlaProperties.Profile profile,
                               String name,
                               int rootAttempts,
                               int modelTurns,
                               int providerFailovers) {
        assertEquals(name, profile.getName());
        assertEquals(rootAttempts, profile.getMaxRootModelAttempts());
        assertEquals(modelTurns, profile.getMaxModelTurns());
        assertEquals(providerFailovers, profile.getMaxProviderFailoverAttempts());
    }
}
