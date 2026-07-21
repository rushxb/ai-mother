package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.memory.long-term")
public class MilvusMemoryProperties {
    private static final Pattern RESOURCE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,254}");

    private boolean enabled = false;
    private String uri = "";
    private String token = "";
    private boolean authenticationRequired = false;
    private boolean tlsRequired = false;
    @NotBlank
    private String databaseName = "default";
    @NotBlank
    private String collectionName = "generation_memory_v2";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private Duration readinessTimeout = Duration.ofSeconds(60);
    private Duration readinessRefreshInterval = Duration.ofSeconds(30);
    private boolean verifyOnStartup = false;
    @Min(10)
    @Max(100000)
    private int fallbackMaxEntries = 5000;
    private Duration fallbackRetention = Duration.ofHours(12);
    @Min(1)
    @Max(50)
    private int defaultTopK = 6;
    private double minimumScore = 0.45;

    @AssertTrue(message = "Milvus memory durations and score must be valid")
    public boolean isConfigurationValid() {
        if (!positive(connectTimeout) || !positive(requestTimeout)
                || !positive(readinessTimeout) || !positive(readinessRefreshInterval)
                || !positive(fallbackRetention)
                || minimumScore < -1.0 || minimumScore > 1.0
                || !RESOURCE_NAME.matcher(databaseName).matches()
                || !RESOURCE_NAME.matcher(collectionName).matches()) {
            return false;
        }
        if (!enabled) {
            return true;
        }
        return validEndpoint(uri)
                && (!authenticationRequired || token != null && !token.isBlank());
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean validEndpoint(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            URI endpoint = new URI(value.trim());
            String scheme = endpoint.getScheme();
            boolean supportedScheme = "http".equalsIgnoreCase(scheme)
                    || "https".equalsIgnoreCase(scheme);
            return supportedScheme
                    && (!tlsRequired || "https".equalsIgnoreCase(scheme))
                    && endpoint.getHost() != null
                    && !endpoint.getHost().isBlank()
                    && endpoint.getUserInfo() == null
                    && endpoint.getQuery() == null
                    && endpoint.getFragment() == null
                    && (endpoint.getPath() == null
                    || endpoint.getPath().isBlank()
                    || "/".equals(endpoint.getPath()));
        } catch (URISyntaxException invalidEndpoint) {
            return false;
        }
    }
}
