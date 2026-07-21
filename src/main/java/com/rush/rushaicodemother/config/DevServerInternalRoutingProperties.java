package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/** Internal node-to-node routing and authentication for durable Preview sessions. */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.dev-server.internal-routing")
public class DevServerInternalRoutingProperties {

    private static final String NODE_ID_PLACEHOLDER = "{nodeId}";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /** Resolves a durable node id to that node's internal application base URL. */
    private String baseUrlTemplate = "http://{nodeId}:8123/api";

    /** Shared HMAC secret. It may be blank in single-node development, but is mandatory in prod. */
    private String sharedSecret = "";

    /** Maximum accepted difference between sender and receiver clocks. */
    private Duration allowedClockSkew = Duration.ofSeconds(30);

    /** Bounded nonce cache used to reject replayed internal requests. */
    @Min(100)
    @Max(1_000_000)
    private int replayCacheMaxEntries = 10_000;

    @AssertTrue(message = "Dev Server internal base URL template must be a safe HTTP(S) URL containing {nodeId}")
    public boolean isBaseUrlTemplateValid() {
        if (baseUrlTemplate == null || !baseUrlTemplate.contains(NODE_ID_PLACEHOLDER)) {
            return false;
        }
        try {
            URI uri = URI.create(baseUrlTemplate.replace(NODE_ID_PLACEHOLDER, "preview-node-a"));
            String scheme = uri.getScheme();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && (uri.getPath() == null || !uri.getPath().contains(".."));
        } catch (RuntimeException invalidUri) {
            return false;
        }
    }

    @AssertTrue(message = "Dev Server internal shared secret must contain at least 32 characters when configured")
    public boolean isSharedSecretSafe() {
        return sharedSecret == null || sharedSecret.isBlank() || sharedSecret.trim().length() >= 32;
    }

    @AssertTrue(message = "Dev Server internal routing clock skew must be positive and at most 5 minutes")
    public boolean isAllowedClockSkewValid() {
        return allowedClockSkew != null
                && !allowedClockSkew.isZero()
                && !allowedClockSkew.isNegative()
                && allowedClockSkew.compareTo(MAX_CLOCK_SKEW) <= 0;
    }

    public boolean hasSharedSecret() {
        return sharedSecret != null && !sharedSecret.isBlank();
    }
}
