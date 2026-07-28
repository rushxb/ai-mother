package com.rush.rushaicodemother.service.aimodel;

import lombok.Builder;
import lombok.ToString;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * AI 模型模块内部的完整配置模型。
 *
 * <p>该对象可能包含 API Key，只允许在 AI 模型模块及运行时模型装配链路中使用，
 * 禁止作为 Controller 响应或跨模块管理查询结果。</p>
 */
@Value
@Builder(toBuilder = true)
public class AiModelConfiguration {

    Long id;
    String modelName;
    String provider;
    String modelId;
    String description;
    String baseUrl;
    @ToString.Exclude
    String secretRef;
    @ToString.Exclude
    String secretFingerprint;
    String secretKeyId;
    Integer maxTokens;
    Double temperature;
    Integer isEnabled;
    String modelType;
    Integer supportsThinking;
    Integer sortOrder;
    String configJson;
    Long userId;
    LocalDateTime editTime;
    LocalDateTime createTime;
    LocalDateTime updateTime;

    /**
 * 启用{@code d}。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean enabled() {
        return Integer.valueOf(1).equals(isEnabled);
    }

    /**
 * 返回{@code thinking}支持的。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean thinkingSupported() {
        return Integer.valueOf(1).equals(supportsThinking);
    }
}
