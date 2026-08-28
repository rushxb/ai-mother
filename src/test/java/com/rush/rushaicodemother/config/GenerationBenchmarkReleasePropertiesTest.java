package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.assertj.core.api.Assertions.assertThat;

class GenerationBenchmarkReleasePropertiesTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GenerationBenchmarkReleaseProperties.class);

    @Test
    void hardcodedFirstPreviewReleaseLimitsMustMatchRouteSla() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();

        assertEquals(GenerationBenchmarkReleaseProperties.MINIMUM_FIRST_PREVIEW_OBSERVATION_RATE,
                properties.getMinimumFirstPreviewObservationRate());
        // 各路由的 P90 首屏门禁必须与对应路由的首屏 SLA 上限保持一致。
        assertEquals(GenerationSlaProperties.READ_ONLY_FIRST_PREVIEW_TIMEOUT,
                maximum(properties, GenerationMode.READ_ONLY));
        assertEquals(GenerationSlaProperties.CREATE_FIRST_PREVIEW_TIMEOUT,
                maximum(properties, GenerationMode.CREATE));
        assertEquals(GenerationSlaProperties.LIGHT_EDIT_FIRST_PREVIEW_TIMEOUT,
                maximum(properties, GenerationMode.LIGHT_EDIT));
        assertEquals(GenerationSlaProperties.AGENT_EDIT_FIRST_PREVIEW_TIMEOUT,
                maximum(properties, GenerationMode.AGENT_EDIT));
        assertEquals(GenerationSlaProperties.HEAVY_EXPERT_FIRST_PREVIEW_TIMEOUT,
                maximum(properties, GenerationMode.HEAVY_EXPERT));
        assertEquals(GenerationBenchmarkReleaseProperties.MAXIMUM_P99_FIRST_TOKEN_LATENCY,
                properties.getMaximumP99FirstTokenLatency());
        assertEquals(GenerationBenchmarkReleaseProperties.MAXIMUM_P99_FIRST_PREVIEW_LATENCY,
                properties.getMaximumP99FirstPreviewLatency());
        assertTrue(properties.isDurationConfigurationValid());
    }

    /** 发布门禁阈值固定为常量，外部配置不得放宽首屏与通过率要求。 */
    @Test
    void externalPropertiesMustNotRelaxHardcodedReleaseGate() {
        contextRunner
                .withPropertyValues(
                        "app.generation-benchmark.release-gate.minimum-first-preview-observation-rate=0.5",
                        "app.generation-benchmark.release-gate.minimum-success-rate=0.1",
                        "app.generation-benchmark.release-gate.maximum-p99-first-token-latency=10m",
                        "app.generation-benchmark.release-gate.maximum-p99-first-preview-latency=30m",
                        "app.generation-benchmark.release-gate.minimum-security-pass-rate=0.1"
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    GenerationBenchmarkReleaseProperties properties =
                            context.getBean(GenerationBenchmarkReleaseProperties.class);
                    assertThat(properties.getMinimumFirstPreviewObservationRate())
                            .isEqualTo(GenerationBenchmarkReleaseProperties
                                    .MINIMUM_FIRST_PREVIEW_OBSERVATION_RATE);
                    assertThat(properties.getMinimumSuccessRate())
                            .isEqualTo(GenerationBenchmarkReleaseProperties.MINIMUM_SUCCESS_RATE);
                    assertThat(properties.getMaximumP99FirstTokenLatency())
                            .isEqualTo(GenerationBenchmarkReleaseProperties
                                    .MAXIMUM_P99_FIRST_TOKEN_LATENCY);
                    assertThat(properties.getMaximumP99FirstPreviewLatency())
                            .isEqualTo(GenerationBenchmarkReleaseProperties
                                    .MAXIMUM_P99_FIRST_PREVIEW_LATENCY);
                    assertThat(properties.getMinimumSecurityPassRate())
                            .isEqualTo(GenerationBenchmarkReleaseProperties.MINIMUM_SECURITY_PASS_RATE);
                });
    }

    @Test
    void incompleteModeLimitsMustBeRejected() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMaximumP90FirstPreviewLatencyByMode(Map.of(
                GenerationMode.CREATE, Duration.ofSeconds(60)
        ));

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void firstPreviewEvidenceCoverageMustRemainComplete() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMinimumFirstPreviewObservationRate(0.99);

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void p99FirstTokenLimitMustNotBeLowerThanP90Limit() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();
        properties.setMaximumP99FirstTokenLatency(Duration.ofSeconds(14));

        assertFalse(properties.isDurationConfigurationValid());
    }

    @Test
    void modelCapacityGateMustMatchRuntimeBudgetAndRejectInvalidLimits() {
        GenerationBenchmarkReleaseProperties properties = new GenerationBenchmarkReleaseProperties();

        assertEquals(GenerationSlaProperties.HEAVY_EXPERT_MAX_PROVIDER_FAILOVER_ATTEMPTS,
                properties.getMaximumPhysicalModelCallsPerTask());
        assertEquals(GenerationBenchmarkReleaseProperties
                        .MAXIMUM_PHYSICAL_MODEL_CALLS_PER_SUCCESSFUL_DELIVERY,
                properties.getMaximumPhysicalModelCallsPerSuccessfulDelivery());
        assertTrue(validator.validate(properties).isEmpty());

        properties.setMaximumPhysicalModelCallsPerTask(0);
        assertFalse(validator.validate(properties).isEmpty());
        properties.setMaximumPhysicalModelCallsPerTask(
                GenerationBenchmarkReleaseProperties.MAXIMUM_PHYSICAL_MODEL_CALLS_PER_TASK);
        properties.setMaximumCapacityFailureRate(1.01);
        assertFalse(validator.validate(properties).isEmpty());
    }

    private Duration maximum(GenerationBenchmarkReleaseProperties properties,
                             GenerationMode mode) {
        return properties.getMaximumP90FirstPreviewLatencyByMode().get(mode);
    }
}
