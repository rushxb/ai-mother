package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationSlaProperties;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/**
 * 生成基准测试发布门禁的固定阈值。
 */
@Data
@Component
@Validated
public class GenerationBenchmarkReleaseProperties {

    public static final int MINIMUM_TASK_COUNT = 32;
    public static final double MINIMUM_SUCCESS_RATE = 0.95;
    public static final double MINIMUM_BUILD_PASS_RATE = 0.90;
    public static final double MINIMUM_STRUCTURAL_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_STRUCTURAL_PASS_RATE = 0.95;
    public static final double MINIMUM_FUNCTIONAL_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_FUNCTIONAL_PASS_RATE = 0.90;
    public static final double MINIMUM_DIFF_SCOPE_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_DIFF_SCOPE_PASS_RATE = 0.95;
    public static final double MINIMUM_SECURITY_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_SECURITY_PASS_RATE = 1.0;
    public static final double MINIMUM_RUNTIME_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_RUNTIME_PASS_RATE = 0.90;
    public static final double MINIMUM_VISUAL_EVALUATION_RATE = 1.0;
    public static final double MINIMUM_VISUAL_PASS_RATE = 0.90;
    public static final boolean REQUIRE_PROMPT_BUNDLE_ID = true;
    public static final double MAXIMUM_FALLBACK_RATE = 0.20;
    public static final double MINIMUM_ROUTE_ACCURACY = 0.90;
    public static final double MAXIMUM_WRONG_ESCALATION_RATE = 0.10;
    public static final double MAXIMUM_WRONG_DEGRADATION_RATE = 0.05;
    public static final Duration MAXIMUM_P90_DURATION = Duration.ofMinutes(5);
    public static final Duration MAXIMUM_P99_DURATION = Duration.ofMinutes(10);
    public static final Duration MAXIMUM_P90_FIRST_TOKEN_LATENCY = Duration.ofSeconds(15);
    public static final Duration MAXIMUM_P99_FIRST_TOKEN_LATENCY = Duration.ofSeconds(30);
    public static final double MINIMUM_FIRST_PREVIEW_OBSERVATION_RATE = 1.0;
    public static final long MAXIMUM_AVERAGE_TOKENS = 250_000L;
    public static final long MAXIMUM_AVERAGE_CREDIT_COST = 10L;

    /** P99 首屏门禁沿用 HEAVY_EXPERT 路由的首屏 SLA 上限。 */
    public static final Duration MAXIMUM_P99_FIRST_PREVIEW_LATENCY =
            GenerationSlaProperties.HEAVY_EXPERT_FIRST_PREVIEW_TIMEOUT;

    @Min(1)
    private int minimumTaskCount = MINIMUM_TASK_COUNT;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSuccessRate = MINIMUM_SUCCESS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumBuildPassRate = MINIMUM_BUILD_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumStructuralEvaluationRate = MINIMUM_STRUCTURAL_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumStructuralPassRate = MINIMUM_STRUCTURAL_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFunctionalEvaluationRate = MINIMUM_FUNCTIONAL_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumFunctionalPassRate = MINIMUM_FUNCTIONAL_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumDiffScopeEvaluationRate = MINIMUM_DIFF_SCOPE_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumDiffScopePassRate = MINIMUM_DIFF_SCOPE_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSecurityEvaluationRate = MINIMUM_SECURITY_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumSecurityPassRate = MINIMUM_SECURITY_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRuntimeEvaluationRate = MINIMUM_RUNTIME_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRuntimePassRate = MINIMUM_RUNTIME_PASS_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumVisualEvaluationRate = MINIMUM_VISUAL_EVALUATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumVisualPassRate = MINIMUM_VISUAL_PASS_RATE;

    private boolean requirePromptBundleId = REQUIRE_PROMPT_BUNDLE_ID;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maximumFallbackRate = MAXIMUM_FALLBACK_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double minimumRouteAccuracy = MINIMUM_ROUTE_ACCURACY;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maximumWrongEscalationRate = MAXIMUM_WRONG_ESCALATION_RATE;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double maximumWrongDegradationRate = MAXIMUM_WRONG_DEGRADATION_RATE;

    @NotNull
    private Duration maximumP90Duration = MAXIMUM_P90_DURATION;

    @NotNull
    private Duration maximumP99Duration = MAXIMUM_P99_DURATION;

    @NotNull
    private Duration maximumP90FirstTokenLatency = MAXIMUM_P90_FIRST_TOKEN_LATENCY;

    @NotNull
    private Duration maximumP99FirstTokenLatency = MAXIMUM_P99_FIRST_TOKEN_LATENCY;

    @DecimalMin("1.0")
    @DecimalMax("1.0")
    private double minimumFirstPreviewObservationRate = MINIMUM_FIRST_PREVIEW_OBSERVATION_RATE;

    @NotNull
    private Map<GenerationMode, Duration> maximumP90FirstPreviewLatencyByMode =
            defaultMaximumP90FirstPreviewLatencyByMode();

    @NotNull
    private Duration maximumP99FirstPreviewLatency = MAXIMUM_P99_FIRST_PREVIEW_LATENCY;

    @Min(1)
    private long maximumAverageTokens = MAXIMUM_AVERAGE_TOKENS;

    @Min(1)
    private long maximumAverageCreditCost = MAXIMUM_AVERAGE_CREDIT_COST;

    /**
     * 读取每次成功交付允许消耗的最大 token。
     *
     * <p>底层字段名为兼容既有配置键继续保留；发布门禁不再按全部尝试摊薄失败成本。</p>
     */
    public long maximumTokensPerSuccessfulDelivery() {
        return maximumAverageTokens;
    }

    /** 读取每次成功交付允许扣除的最大积分，配置兼容策略同 token 阈值。 */
    public long maximumCreditCostPerSuccessfulDelivery() {
        return maximumAverageCreditCost;
    }

    /**
 * 校验各时长配置及其相互约束是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "生成质量评测的耗时门禁配置无效")
    public boolean isDurationConfigurationValid() {
        if (!positive(maximumP90Duration)
                || !positive(maximumP99Duration)
                || !positive(maximumP90FirstTokenLatency)
                || !positive(maximumP99FirstTokenLatency)
                || !positive(maximumP99FirstPreviewLatency)
                || Double.compare(minimumFirstPreviewObservationRate, 1.0) != 0
                || maximumP90Duration.compareTo(maximumP99Duration) > 0
                || maximumP90FirstTokenLatency.compareTo(maximumP99FirstTokenLatency) > 0
                || maximumP90FirstPreviewLatencyByMode == null
                || maximumP90FirstPreviewLatencyByMode.size() != GenerationMode.values().length) {
            return false;
        }
        for (GenerationMode mode : GenerationMode.values()) {
            Duration maximum = maximumP90FirstPreviewLatencyByMode.get(mode);
            if (!positive(maximum) || maximum.compareTo(maximumP99FirstPreviewLatency) > 0) {
                return false;
            }
        }
        return true;
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }

    /** 各路由的 P90 首屏门禁沿用对应路由的首屏 SLA 上限。 */
    private static Map<GenerationMode, Duration> defaultMaximumP90FirstPreviewLatencyByMode() {
        EnumMap<GenerationMode, Duration> limits = new EnumMap<>(GenerationMode.class);
        limits.put(GenerationMode.READ_ONLY,
                GenerationSlaProperties.READ_ONLY_FIRST_PREVIEW_TIMEOUT);
        limits.put(GenerationMode.CREATE,
                GenerationSlaProperties.CREATE_FIRST_PREVIEW_TIMEOUT);
        limits.put(GenerationMode.LIGHT_EDIT,
                GenerationSlaProperties.LIGHT_EDIT_FIRST_PREVIEW_TIMEOUT);
        limits.put(GenerationMode.AGENT_EDIT,
                GenerationSlaProperties.AGENT_EDIT_FIRST_PREVIEW_TIMEOUT);
        limits.put(GenerationMode.HEAVY_EXPERT,
                GenerationSlaProperties.HEAVY_EXPERT_FIRST_PREVIEW_TIMEOUT);
        return limits;
    }
}
