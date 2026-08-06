package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis 业务缓存的固定过期策略。
 */
@Data
@Component
@Validated
public class RedisCacheProperties {

    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    public static final Map<String, Duration> CACHE_TTL = Map.of("good_app_page", Duration.ofMinutes(5));

    private Duration defaultTtl = DEFAULT_TTL;

    private Map<String, Duration> cacheTtl = new LinkedHashMap<>(CACHE_TTL);

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "Redis 缓存 TTL 必须全部大于 0，缓存名称不得为空")
    public boolean isConfigurationValid() {
        return isPositive(defaultTtl)
                && cacheTtl != null
                && cacheTtl.entrySet().stream().allMatch(entry ->
                entry.getKey() != null
                        && !entry.getKey().isBlank()
                        && isPositive(entry.getValue()));
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
