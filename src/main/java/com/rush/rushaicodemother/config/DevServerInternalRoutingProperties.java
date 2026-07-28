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

/** 用于持久预览会话的内部节点到节点路由和身份验证。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.dev-server.internal-routing")
public class DevServerInternalRoutingProperties {

    private static final String NODE_ID_PLACEHOLDER = "{nodeId}";
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    /** 将持久节点 ID 解析为该节点的内部应用程序基本 URL。 */
    private String baseUrlTemplate = "http://{nodeId}:8123/api";

    /** 共享 HMAC 秘密。在单节点开发中可能为空，但在产品中是强制的。 */
    private String sharedSecret = "";

    /** 发送器和接收器时钟之间可接受的最大差异。 */
    private Duration allowedClockSkew = Duration.ofSeconds(30);

    /** 有界随机数缓存用于拒绝重播的内部请求。 */
    @Min(100)
    @Max(1_000_000)
    private int replayCacheMaxEntries = 10_000;

    /**
 * 判断基础地址模板是否有效。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
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
