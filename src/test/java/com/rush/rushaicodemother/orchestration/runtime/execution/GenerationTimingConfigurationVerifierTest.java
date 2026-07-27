package com.rush.rushaicodemother.orchestration.runtime.execution;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationTimingConfigurationVerifierTest {

    @Test
    void defaultConfigurationMustCoverEveryCompletionWindow() {
        assertDoesNotThrow(verifier()::afterSingletonsInstantiated);
    }

    @Test
    void routeTotalTimeoutMustCoverTheLargestProjectCompletionWindow() {
        GenerationSlaProperties slaProperties = new GenerationSlaProperties();
        GenerationSlaProperties.Profile create = slaProperties.profile(GenerationMode.CREATE);
        create.setTotalTimeout(Duration.ofSeconds(101));
        create.setModelCallTimeout(Duration.ofSeconds(90));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifier(slaProperties, new GenerationRuntimeProperties())
                        .afterSingletonsInstantiated()
        );

        assertTrue(exception.getMessage().contains(
                "app.generation-sla.profiles.CREATE.total-timeout"));
        assertTrue(exception.getMessage().contains("102000ms"));
    }

    @Test
    void saturatedProfileMustAlsoCoverTheCompletionWindow() {
        GenerationSlaProperties slaProperties = new GenerationSlaProperties();
        slaProperties.getSaturatedAgentEdit().setTotalTimeout(Duration.ofSeconds(90));
        slaProperties.getSaturatedAgentEdit().setModelCallTimeout(Duration.ofSeconds(60));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifier(slaProperties, new GenerationRuntimeProperties())
                        .afterSingletonsInstantiated()
        );

        assertTrue(exception.getMessage().contains(
                "app.generation-sla.saturated-agent-edit.total-timeout"));
    }

    @Test
    void modelCallTimeoutMustLeaveAnEffectiveModelTurn() {
        GenerationSlaProperties slaProperties = new GenerationSlaProperties();
        slaProperties.profile(GenerationMode.LIGHT_EDIT)
                .setModelCallTimeout(Duration.ofSeconds(29));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifier(slaProperties, new GenerationRuntimeProperties())
                        .afterSingletonsInstantiated()
        );

        assertTrue(exception.getMessage().contains(
                "app.generation-sla.profiles.LIGHT_EDIT.model-call-timeout"));
    }

    @Test
    void legacyRuntimeMustObeyTheSameCompletionContract() {
        GenerationRuntimeProperties runtimeProperties = new GenerationRuntimeProperties();
        runtimeProperties.setTaskTimeout(Duration.ofSeconds(101));
        runtimeProperties.setModelCallTimeout(Duration.ofSeconds(90));

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> verifier(new GenerationSlaProperties(), runtimeProperties)
                        .afterSingletonsInstantiated()
        );

        assertTrue(exception.getMessage().contains("app.generation-runtime.task-timeout"));
    }

    private GenerationTimingConfigurationVerifier verifier() {
        return verifier(new GenerationSlaProperties(), new GenerationRuntimeProperties());
    }

    private GenerationTimingConfigurationVerifier verifier(
            GenerationSlaProperties slaProperties,
            GenerationRuntimeProperties runtimeProperties) {
        return new GenerationTimingConfigurationVerifier(
                slaProperties,
                runtimeProperties,
                new GenerationStageAdmissionProperties()
        );
    }
}
