package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 对话记忆的 Redis 命名空间、过期时间与进程内故障回退容量。
 *
 * <p>回退容量同时约束同步副本和待回灌变更；容量不足时优先淘汰同步副本，
 * 不允许静默丢弃待回灌变更。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.chat-memory")
public class ChatMemoryProperties {

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,128}")
    private String keyPrefix = "chat-memory:";

    @Min(1)
    private long ttlSeconds = 3600;

    @Min(1)
    private long fallbackMaxEntries = 1000;

    @NotNull
    private Duration fallbackExpireAfterAccess = Duration.ofHours(2);

    @AssertTrue(message = "对话记忆内存回退过期时间必须大于 0")
    public boolean isFallbackExpirationValid() {
        return fallbackExpireAfterAccess != null
                && !fallbackExpireAfterAccess.isZero()
                && !fallbackExpireAfterAccess.isNegative();
    }
}
