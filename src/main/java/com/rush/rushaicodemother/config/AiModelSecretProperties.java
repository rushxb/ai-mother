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

/** Key-ring configuration for envelope-encrypted AI provider credentials. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-model-secrets")
public class AiModelSecretProperties {

    private String activeKeyId;

    /** Base64-encoded 256-bit key-encryption key. */
    @ToString.Exclude
    private String activeKey;

    /** Base64-encoded 256-bit HMAC key kept stable across encryption-key rotations. */
    @ToString.Exclude
    private String fingerprintKey;

    /** Previous key-encryption keys retained only while old references are being rewrapped. */
    @ToString.Exclude
    private Map<String, String> previousKeys = new LinkedHashMap<>();

    @Min(32)
    @Max(8_192)
    private int maxSecretBytes = 2_048;

    @Min(512)
    @Max(32_768)
    private int maxReferenceBytes = 4_096;
}
