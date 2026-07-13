package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Value;

/**
 * 普通用户可见的 AI 模型信息。
 * 该对象刻意不包含 API Key、基础地址和扩展配置等运行时敏感信息。
 */
@Value
@Builder
public class AiModelPublicVO {
    Long id;
    String modelName;
    String provider;
    String modelId;
    String description;
    String modelType;
    Integer supportsThinking;
    Integer sortOrder;
}