package com.rush.rushaicodemother.model.dto.aimodel;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.ToString;

/**
 * 更新 AI 模型配置请求。
 * API Key 为空时仅在 API 网络主体未变化的情况下保留现有密钥；
 * 更换协议、主机或端口必须同时提交新密钥。
 */
@Data
public class AiModelUpdateRequest {

    @NotNull
    @Positive
    private Long id;

    @Size(max = 100)
    private String modelName;

    @Size(max = 50)
    private String provider;

    @Size(max = 100)
    private String modelId;

    @Size(max = 500)
    private String description;

    @Size(max = 500)
    private String baseUrl;

    @Size(max = 2048)
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @ToString.Exclude
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
