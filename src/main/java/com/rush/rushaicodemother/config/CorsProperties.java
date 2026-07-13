package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 跨域访问白名单配置。
 *
 * <p>生产环境必须显式配置允许的前端 Origin，禁止使用通配符与凭证组合，
 * 避免任意站点携带用户 Cookie 调用后端接口。</p>
 */
@Data
@Validated
@ConfigurationProperties(prefix = "app.cors")
public class CorsProperties {

    private static final Set<String> SUPPORTED_METHODS = Set.of(
            "GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
    );

    /** 允许访问 API 的完整 Origin，例如 https://console.example.com。 */
    @NotEmpty(message = "至少需要配置一个允许跨域访问的 Origin")
    private List<String> allowedOrigins = new ArrayList<>();

    /** 是否允许浏览器携带 Cookie 等凭证。 */
    private boolean allowCredentials = true;

    /** 允许的 HTTP 方法。 */
    @NotEmpty
    private List<String> allowedMethods = new ArrayList<>(SUPPORTED_METHODS);

    /** 允许客户端发送的请求头。 */
    @NotEmpty
    private List<String> allowedHeaders = new ArrayList<>(List.of(
            "Accept", "Authorization", "Content-Type", "X-Requested-With"
    ));

    /** 允许浏览器脚本读取的响应头。 */
    private List<String> exposedHeaders = new ArrayList<>(List.of("Content-Disposition"));

    /** 浏览器缓存预检结果的时长。 */
    private Duration maxAge = Duration.ofHours(1);

    @AssertTrue(message = "CORS 预检缓存时长必须在 0 到 24 小时之间")
    public boolean isMaxAgeSafe() {
        return maxAge != null && !maxAge.isNegative() && maxAge.compareTo(Duration.ofDays(1)) <= 0;
    }

    @AssertTrue(message = "CORS Origin 必须是无通配符、无路径的 http/https 完整来源地址")
    public boolean isAllowedOriginsSafe() {
        return allowedOrigins != null && allowedOrigins.stream().allMatch(this::isSafeOrigin);
    }

    @AssertTrue(message = "CORS 方法包含不支持的 HTTP 方法")
    public boolean isAllowedMethodsSafe() {
        return allowedMethods != null && !allowedMethods.isEmpty()
                && allowedMethods.stream()
                .map(method -> method == null ? "" : method.toUpperCase(Locale.ROOT))
                .allMatch(SUPPORTED_METHODS::contains);
    }

    private boolean isSafeOrigin(String origin) {
        if (origin == null || origin.isBlank() || origin.contains("*")) {
            return false;
        }
        try {
            URI uri = URI.create(origin);
            String scheme = uri.getScheme();
            boolean supportedScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            boolean validPort = uri.getPort() >= -1 && uri.getPort() <= 65535;
            boolean noPath = uri.getPath() == null || uri.getPath().isEmpty();
            return supportedScheme
                    && uri.getHost() != null
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && noPath
                    && validPort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}