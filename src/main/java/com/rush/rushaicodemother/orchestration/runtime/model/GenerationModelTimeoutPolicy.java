package com.rush.rushaicodemother.orchestration.runtime.model;

import com.rush.rushaicodemother.config.AiModelRuntimeProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;

/** 统一计算模型首个有效信号与整回合之间的分层超时。 */
@Component
public final class GenerationModelTimeoutPolicy {

    private final Duration configuredFirstSignalTimeout;

    public GenerationModelTimeoutPolicy(AiModelRuntimeProperties properties) {
        Objects.requireNonNull(properties, "AI 模型运行配置不能为空");
        this.configuredFirstSignalTimeout = requirePositive(
                properties.getFirstSignalTimeout(), "模型首信号超时必须大于 0");
    }

    /** 首信号窗口不得越过调用方已经按任务截止时间收紧的整回合窗口。 */
    public Duration firstSignalTimeout(Duration modelTurnTimeout) {
        Duration boundedTurnTimeout = requirePositive(modelTurnTimeout, "模型回合超时必须大于 0");
        return configuredFirstSignalTimeout.compareTo(boundedTurnTimeout) <= 0
                ? configuredFirstSignalTimeout
                : boundedTurnTimeout;
    }

    public static GenerationModelTimeoutPolicy defaults() {
        return new GenerationModelTimeoutPolicy(new AiModelRuntimeProperties());
    }

    private Duration requirePositive(Duration value, String message) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
