package com.rush.rushaicodemother.model.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 支持的 AI 模型目录项
 */
@Data
@Builder
public class SupportedAiModelVO {

    private String provider;

    private String providerLabel;

    private String modelId;

    private String modelName;

    private String defaultBaseUrl;

    private String defaultProtocol;

    private List<String> supportedProtocols;

    private List<String> supportedModelTypes;

    private String defaultModelType;

    private Integer supportsThinking;

    private Integer defaultMaxTokens;

    private Double defaultTemperature;

    private Double minTemperature;

    private Double maxTemperature;
}
