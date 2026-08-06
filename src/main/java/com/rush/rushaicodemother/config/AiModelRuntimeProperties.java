package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 数据库 AI 模型的运行期行为配置。
 *
 * <p>模型地址、凭据和模型名称统一由数据库模型目录管理；此处只保留日志、超时和重试等
 * 与具体模型无关的运行参数。</p>
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-runtime")
public class AiModelRuntimeProperties {

    public static final Duration GENERATION_TIMEOUT = Duration.ofMinutes(4);
    public static final Duration FIRST_SIGNAL_TIMEOUT = Duration.ofSeconds(45);
    public static final Duration ROUTING_TIMEOUT = Duration.ofSeconds(30);
    public static final int ROUTING_MAX_RETRIES = 0;
    public static final int FAILOVER_MAX_CANDIDATES = 2;
    public static final Duration FIRST_TOKEN_HEDGE_DELAY = Duration.ofSeconds(3);
    public static final boolean FIRST_TOKEN_HEDGE_REQUIRE_DISTINCT_PROVIDER = true;
    public static final Duration ROOT_MODEL_RETRY_MIN_DELAY = Duration.ofSeconds(3);
    public static final Duration ROOT_MODEL_RETRY_MAX_DELAY = Duration.ofSeconds(20);
    public static final double ROOT_MODEL_RETRY_JITTER = 0.35;
    public static final Duration CREATE_SPEC_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_ROUTING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MAX_GENERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration MAX_CREATE_SPEC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MIN_FIRST_SIGNAL_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_FIRST_SIGNAL_TIMEOUT = Duration.ofMinutes(2);
    private static final Duration MIN_FIRST_TOKEN_HEDGE_DELAY = Duration.ofMillis(250);
    private static final Duration MAX_FIRST_TOKEN_HEDGE_DELAY = Duration.ofSeconds(30);
    private static final Duration MIN_ROOT_MODEL_RETRY_DELAY = Duration.ofMillis(100);
    private static final Duration MAX_ROOT_MODEL_RETRY_DELAY = Duration.ofMinutes(1);

    private boolean generationLogRequests;

    private boolean generationLogResponses;

    private boolean routingLogRequests;

    private boolean routingLogResponses;

    /** 单次流式生成请求的 HTTP 超时；任务级 Deadline 由生成执行上下文统一控制。 */
    private Duration generationTimeout = GENERATION_TIMEOUT;

    /** 模型尚未产生文本、思考或工具事件时允许等待的最长时间。 */
    private Duration firstSignalTimeout = FIRST_SIGNAL_TIMEOUT;

    private Duration routingTimeout = ROUTING_TIMEOUT;

    @Min(0)
    @Max(5)
    private int routingMaxRetries = ROUTING_MAX_RETRIES;

    /** 单次请求最多允许参与故障切换的模型候选数。 */
    @Min(1)
    @Max(5)
    private int failoverMaxCandidates = FAILOVER_MAX_CANDIDATES;

    /** HEAVY 路径是否优先使用高置信本地规则，仅把模糊意图交给路由模型。 */
    private boolean localFirstHeavyRoutingEnabled = true;

    /** 是否在首个有效流事件前对慢请求启动一个影子候选。 */
    private boolean firstTokenHedgeEnabled;

    /** 启动影子候选前等待主候选输出的时间。 */
    private Duration firstTokenHedgeDelay = FIRST_TOKEN_HEDGE_DELAY;

    /** 是否要求主候选和影子候选来自不同供应商。 */
    private boolean firstTokenHedgeRequireDistinctProvider = FIRST_TOKEN_HEDGE_REQUIRE_DISTINCT_PROVIDER;

    /** 根模型尝试失败后，刷新健康模型池前的最小等待时间。 */
    private Duration rootModelRetryMinDelay = ROOT_MODEL_RETRY_MIN_DELAY;

    /** 根模型重试指数退避允许达到的最大等待时间。 */
    private Duration rootModelRetryMaxDelay = ROOT_MODEL_RETRY_MAX_DELAY;

    /** 根模型重试退避的随机抖动比例。 */
    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private double rootModelRetryJitter = ROOT_MODEL_RETRY_JITTER;

    private Duration createSpecTimeout = CREATE_SPEC_TIMEOUT;

    @AssertTrue(message = "AI 流式生成模型超时必须在 3 秒到 15 分钟之间")
    public boolean isGenerationTimeoutValid() {
        return isWithinRange(generationTimeout, MIN_TIMEOUT, MAX_GENERATION_TIMEOUT);
    }

    @AssertTrue(message = "模型首信号超时必须在 3 秒到 2 分钟之间，且不得超过整次生成超时")
    public boolean isFirstSignalTimeoutValid() {
        return isWithinRange(firstSignalTimeout, MIN_FIRST_SIGNAL_TIMEOUT, MAX_FIRST_SIGNAL_TIMEOUT)
                && generationTimeout != null
                && firstSignalTimeout.compareTo(generationTimeout) <= 0;
    }

    @AssertTrue(message = "AI 路由模型超时必须在 3 秒到 5 分钟之间")
    public boolean isRoutingTimeoutValid() {
        return isWithinRange(routingTimeout, MIN_TIMEOUT, MAX_ROUTING_TIMEOUT);
    }

    @AssertTrue(message = "CREATE Spec 模型超时必须在 3 秒到 10 秒之间")
    public boolean isCreateSpecTimeoutValid() {
        return isWithinRange(createSpecTimeout, MIN_TIMEOUT, MAX_CREATE_SPEC_TIMEOUT);
    }

    @AssertTrue(message = "首 Token 对冲延迟必须在 250 毫秒到 30 秒之间")
    public boolean isFirstTokenHedgeDelayValid() {
        return isWithinRange(
                firstTokenHedgeDelay,
                MIN_FIRST_TOKEN_HEDGE_DELAY,
                MAX_FIRST_TOKEN_HEDGE_DELAY
        );
    }

    @AssertTrue(message = "根模型重试最小延迟必须在 100 毫秒到 1 分钟之间")
    public boolean isRootModelRetryMinDelayValid() {
        return isWithinRange(
                rootModelRetryMinDelay,
                MIN_ROOT_MODEL_RETRY_DELAY,
                MAX_ROOT_MODEL_RETRY_DELAY
        );
    }

    @AssertTrue(message = "根模型重试最大延迟必须不小于最小延迟且不超过 1 分钟")
    public boolean isRootModelRetryMaxDelayValid() {
        return rootModelRetryMaxDelay != null
                && rootModelRetryMinDelay != null
                && rootModelRetryMaxDelay.compareTo(rootModelRetryMinDelay) >= 0
                && rootModelRetryMaxDelay.compareTo(MAX_ROOT_MODEL_RETRY_DELAY) <= 0;
    }

    private boolean isWithinRange(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
