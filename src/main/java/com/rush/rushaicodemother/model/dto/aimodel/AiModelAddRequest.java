package com.rush.rushaicodemother.model.dto.aimodel;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增 AI 模型配置请求。
 */
@Data
public class AiModelAddRequest {

    @NotBlank
    @Size(max = 100)
    private String modelName;

    @NotBlank
    @Size(max = 50)
    private String provider;

    @NotBlank
    @Size(max = 100)
    private String modelId;

    @Size(max = 500)
    private String description;

    @NotBlank
    @Size(max = 500)
    private String baseUrl;

    @NotBlank
    @Size(max = 2048)
    private String apiKey;

    @Min(1)
    @Max(1_000_000)
    private Integer maxTokens;

    @DecimalMin("0.0")
    @DecimalMax("2.0")
    private Double temperature;

    @Min(0)
    @Max(1)
    private Integer isEnabled;

    @Size(max = 30)
    private String modelType;

    @Min(0)
    @Max(1)
    private Integer supportsThinking;

    private Integer sortOrder;

    @Size(max = 10_000)
    private String configJson;

    @Size(max = 100)
    private String protocol;
}