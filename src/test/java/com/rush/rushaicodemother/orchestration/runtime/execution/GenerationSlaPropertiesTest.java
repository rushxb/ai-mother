package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationSlaPropertiesTest {

    @Test
    void applicationYamlBindsEveryRouteProfile() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));

        GenerationSlaProperties properties = Binder.get(environment)
                .bind("app.generation-sla", Bindable.of(GenerationSlaProperties.class))
                .orElseThrow(() -> new AssertionError("生成任务 SLA 配置未绑定"));

        assertTrue(properties.isConfigurationValid());
        assertProfile(properties.profile(GenerationMode.CREATE),
                "create-preview-first", 4, 18, 4);
        assertProfile(properties.profile(GenerationMode.LIGHT_EDIT),
                "light-edit-fast", 2, 4, 2);
        assertProfile(properties.profile(GenerationMode.AGENT_EDIT),
                "agent-edit-balanced", 2, 12, 4);
        assertProfile(properties.profile(GenerationMode.HEAVY_EXPERT),
                "heavy-expert-quality", 4, 24, 6);
        assertProfile(properties.getSaturatedAgentEdit(),
                "agent-edit-saturated", 2, 8, 2);
        assertEquals(Duration.ofSeconds(45),
                properties.profile(GenerationMode.CREATE).getFirstPreviewCompletionReserve());
        assertEquals(Duration.ofSeconds(30),
                properties.profile(GenerationMode.LIGHT_EDIT).getFirstPreviewCompletionReserve());
        assertEquals(Duration.ofSeconds(45),
                properties.profile(GenerationMode.AGENT_EDIT).getFirstPreviewCompletionReserve());
        assertEquals(Duration.ofSeconds(60),
                properties.profile(GenerationMode.HEAVY_EXPERT).getFirstPreviewCompletionReserve());
        assertEquals(Duration.ofSeconds(30),
                properties.getSaturatedAgentEdit().getFirstPreviewCompletionReserve());
    }

    @Test
    void newBudgetVariablesOverrideLegacyRootAttemptFallback() throws Exception {
        StandardEnvironment environment = environmentWith(Map.of(
                "GENERATION_CREATE_MAX_ROOT_MODEL_ATTEMPTS", "5",
                "GENERATION_CREATE_MAX_MODEL_ATTEMPTS", "7",
                "GENERATION_CREATE_MAX_MODEL_TURNS", "23",
                "GENERATION_CREATE_MAX_PROVIDER_FAILOVER_ATTEMPTS", "9"
        ));

        GenerationSlaProperties properties = bind(environment);

        assertProfile(properties.profile(GenerationMode.CREATE),
                "create-preview-first", 5, 23, 9);
    }

    @Test
    void legacyBudgetVariableRemainsACompatibleRootAttemptFallback() throws Exception {
        GenerationSlaProperties properties = bind(environmentWith(Map.of(
                "GENERATION_CREATE_MAX_MODEL_ATTEMPTS", "6"
        )));

        assertEquals(6, properties.profile(GenerationMode.CREATE).getMaxRootModelAttempts());
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

    private GenerationSlaProperties bind(StandardEnvironment environment) {
        return Binder.get(environment)
                .bind("app.generation-sla", Bindable.of(GenerationSlaProperties.class))
                .orElseThrow(() -> new AssertionError("生成任务 SLA 配置未绑定"));
    }

    private StandardEnvironment environmentWith(Map<String, Object> overrides) throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-overrides", overrides));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        return environment;
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
