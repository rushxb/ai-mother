package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Redis 对话记忆连接与过期配置。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "spring.data.redis")
public class RedisChatMemoryProperties {

    @NotBlank
    private String host;

    @Min(1)
    @Max(65535)
    private int port = 6379;

    private String password;

    private long ttl = 3600;

    @AssertTrue(message = "Redis 对话记忆 TTL 必须大于 0")
    public boolean isTtlValid() {
        return ttl > 0;
    }
}
