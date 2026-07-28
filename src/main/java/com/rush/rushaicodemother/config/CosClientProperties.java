package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * 腾讯云 COS 客户端配置。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "cos.client")
public class CosClientProperties {

    private boolean enabled;

    private String host;

    private String secretId;

    private String secretKey;

    private String region;

    private String bucket;

    @AssertTrue(message = "启用 COS 时必须完整配置 host、secret-id、secret-key、region 和 bucket")
    public boolean isEnabledConfigurationComplete() {
        return !enabled || hasText(host)
                && hasText(secretId)
                && hasText(secretKey)
                && hasText(region)
                && hasText(bucket);
    }

    /**
 * 判断主机是否有效。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "COS host 必须是合法的 http/https 根地址")
    public boolean isHostValid() {
        if (!hasText(host)) {
            return !enabled;
        }
        try {
            URI uri = URI.create(host.trim());
            boolean supportedScheme = "http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme());
            boolean rootPath = uri.getPath() == null || uri.getPath().isEmpty() || "/".equals(uri.getPath());
            return supportedScheme
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && rootPath
                    && uri.getPort() >= -1
                    && uri.getPort() <= 65535;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
