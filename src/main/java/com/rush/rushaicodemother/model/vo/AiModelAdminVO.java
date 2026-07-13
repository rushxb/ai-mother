package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

/**
 * 管理端 AI 模型配置视图。
 * 只暴露密钥是否已配置，不返回密钥原文或掩码，避免前端误回传覆盖。
 */
@Value
@Builder
public class AiModelAdminVO {
    Long id;
    String modelName;
    String provider;
    String modelId;
    String description;
    String baseUrl;
    boolean apiKeyConfigured;
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
}