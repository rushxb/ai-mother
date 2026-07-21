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

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.ai-prompt-catalog")
public class AiPromptCatalogProperties {

    private boolean enabled = true;
    private String manifest = "classpath:prompt/prompt-catalog.json";
    private String rolloutSalt = "ai-code-mother-prompt-rollout-v1";

    @Valid
    private RuntimeReleases runtimeReleases = new RuntimeReleases();

    @Valid
    private Map<String, Release> releases = new LinkedHashMap<>();

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
        private String stableVersion = "";
        private String canaryVersion = "";

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
        private boolean enabled;
        private boolean initialLoadRequired;
        private Duration refreshInterval = Duration.ofSeconds(5);

        public boolean isConfigurationValid() {
            return refreshInterval != null
                    && !refreshInterval.isNegative()
                    && !refreshInterval.isZero()
                    && refreshInterval.compareTo(Duration.ofMinutes(5)) <= 0;
        }
    }
}
