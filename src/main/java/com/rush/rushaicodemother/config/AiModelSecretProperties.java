package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;

/** 信封加密的 AI 提供商凭证的密钥环配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-secrets")
public class AiModelSecretProperties {

    private String activeKeyId;

    /** Base64 编码的 256 位密钥加密密钥。 */
    @ToString.Exclude
    private String activeKey;

    /** Base64 编码的 256 位 HMAC 密钥在加密密钥轮换中保持稳定。 */
    @ToString.Exclude
    private String fingerprintKey;

    /** 仅当旧引用被重新包装时才会保留以前的密钥加密密钥。 */
    @ToString.Exclude
    private Map<String, String> previousKeys = new LinkedHashMap<>();

    @Min(32)
    @Max(8_192)
    private int maxSecretBytes = 2_048;

    @Min(512)
    @Max(32_768)
    private int maxReferenceBytes = 4_096;
}
