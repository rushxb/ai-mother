package com.rush.rushaicodemother.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Duration;

/**
 * AI 提示词目录配置属性。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-prompt-catalog")
public class AiPromptCatalogProperties {

    /** 内置提示词目录清单的 classpath 位置，随构建产物固定。 */
    public static final String MANIFEST = "classpath:prompt/prompt-catalog.json";

    /**
     * 提示词灰度分桶盐值。
     *
     * <p>该值参与 cohort 哈希，修改会让所有用户重新分桶并使灰度观测数据失去可比性，
     * 因此固定为常量：只有在需要主动重置灰度分布时才随版本号一起递增。</p>
     */
    public static final String ROLLOUT_SALT = "ai-code-mother-prompt-rollout-v1";

    /** 是否启用。 */
    private boolean enabled = true;
    private String manifest = MANIFEST;
    private String rolloutSalt = ROLLOUT_SALT;

    @Valid
    private RuntimeReleases runtimeReleases = new RuntimeReleases();

    @Valid
    private Map<String, Release> releases = new LinkedHashMap<>();

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "AI prompt catalog configuration is invalid")
    public boolean isConfigurationValid() {
        if (!enabled) {
            return true;
        }
        if (manifest == null || manifest.isBlank() || rolloutSalt == null || rolloutSalt.isBlank()) {
            return false;
        }
        if (releases == null || runtimeReleases == null || !runtimeReleases.isConfigurationValid()) {
            return false;
        }
        return releases.entrySet().stream().allMatch(entry ->
                entry.getKey() != null
                        && !entry.getKey().isBlank()
                        && entry.getValue() != null
                        && entry.getValue().isConfigurationValid());
    }

    @Data
    public static class Release {
        /** 稳定版本。 */
        private String stableVersion = "";
        /** 灰度版本。 */
        private String canaryVersion = "";

        /** 灰度发布比例。 */
        @Min(0)
        @Max(100)
        private int canaryPercentage;

        public boolean isConfigurationValid() {
            return canaryPercentage == 0
                    || canaryVersion != null && !canaryVersion.isBlank();
        }
    }

    @Data
    public static class RuntimeReleases {

        public static final Duration REFRESH_INTERVAL = Duration.ofSeconds(5);

        /** 是否启用。 */
        private boolean enabled;
        private boolean initialLoadRequired;
        private Duration refreshInterval = REFRESH_INTERVAL;

        public boolean isConfigurationValid() {
            return refreshInterval != null
                    && !refreshInterval.isNegative()
                    && !refreshInterval.isZero()
                    && refreshInterval.compareTo(Duration.ofMinutes(5)) <= 0;
        }
    }
}
