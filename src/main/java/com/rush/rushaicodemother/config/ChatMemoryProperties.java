package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
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

    /** 对话记忆 Redis 键前缀；多环境通过 Redis database 编号隔离，不依赖前缀区分。 */
    public static final String KEY_PREFIX = "chat-memory:";

    public static final long TTL_SECONDS = 3600;
    public static final long FALLBACK_MAX_ENTRIES = 1000;
    public static final Duration FALLBACK_EXPIRE_AFTER_ACCESS = Duration.ofHours(2);
    public static final int COMPLETED_TOOL_ARGUMENTS_MAX_CHARS = 8_192;

    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9:_-]{1,128}")
    private String keyPrefix = KEY_PREFIX;

    @Min(1)
    private long ttlSeconds = TTL_SECONDS;

    @Min(1)
    private long fallbackMaxEntries = FALLBACK_MAX_ENTRIES;

    @NotNull
    private Duration fallbackExpireAfterAccess = FALLBACK_EXPIRE_AFTER_ACCESS;

    @Min(1_024)
    @Max(262_144)
    private int completedToolArgumentsMaxChars = COMPLETED_TOOL_ARGUMENTS_MAX_CHARS;

    @AssertTrue(message = "对话记忆内存回退过期时间必须大于 0")
    public boolean isFallbackExpirationValid() {
        return fallbackExpireAfterAccess != null
                && !fallbackExpireAfterAccess.isZero()
                && !fallbackExpireAfterAccess.isNegative();
    }
}
