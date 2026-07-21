package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
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

    private static final Duration MIN_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration MAX_ROUTING_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration MAX_GENERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration MAX_CREATE_SPEC_TIMEOUT = Duration.ofSeconds(10);

    private boolean generationLogRequests;

    private boolean generationLogResponses;

    private boolean routingLogRequests;

    private boolean routingLogResponses;

    /** 单次流式生成请求的 HTTP 超时；任务级 Deadline 由生成执行上下文统一控制。 */
    private Duration generationTimeout = Duration.ofMinutes(8);

    private Duration routingTimeout = Duration.ofSeconds(30);

    @Min(0)
    @Max(5)
    private int routingMaxRetries;

    /** Maximum provider/model candidates participating in one request. */
    @Min(1)
    @Max(5)
    private int failoverMaxCandidates = 2;

    private Duration createSpecTimeout = Duration.ofSeconds(10);

    @AssertTrue(message = "AI 流式生成模型超时必须在 3 秒到 15 分钟之间")
    public boolean isGenerationTimeoutValid() {
        return isWithinRange(generationTimeout, MIN_TIMEOUT, MAX_GENERATION_TIMEOUT);
    }

    @AssertTrue(message = "AI 路由模型超时必须在 3 秒到 5 分钟之间")
    public boolean isRoutingTimeoutValid() {
        return isWithinRange(routingTimeout, MIN_TIMEOUT, MAX_ROUTING_TIMEOUT);
    }

    @AssertTrue(message = "CREATE Spec 模型超时必须在 3 秒到 10 秒之间")
    public boolean isCreateSpecTimeoutValid() {
        return isWithinRange(createSpecTimeout, MIN_TIMEOUT, MAX_CREATE_SPEC_TIMEOUT);
    }

    private boolean isWithinRange(Duration value, Duration minimum, Duration maximum) {
        return value != null && value.compareTo(minimum) >= 0 && value.compareTo(maximum) <= 0;
    }
}
