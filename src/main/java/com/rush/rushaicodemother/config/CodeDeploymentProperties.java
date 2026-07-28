package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.net.URI;

/**
 * 应用静态部署配置。
 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "code")
public class CodeDeploymentProperties {

    /** 对外暴露的部署访问根地址。 */
    private String deployHost;

    /**
 * 判断部署主机是否有效。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "应用部署地址必须是合法的 http/https 绝对地址，且不能包含查询参数或片段")
    public boolean isDeployHostValid() {
        if (deployHost == null || deployHost.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(deployHost.trim());
            String scheme = uri.getScheme();
            int port = uri.getPort();
            return ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    && uri.getHost() != null
                    && (port == -1 || (port > 0 && port <= 65535))
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
