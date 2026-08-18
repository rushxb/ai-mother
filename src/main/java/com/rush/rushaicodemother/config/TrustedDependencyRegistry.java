package com.rush.rushaicodemother.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 由平台配置、注入依赖安装容器的唯一 registry 地址。
 *
 * <p>该值对象拒绝本地文件、回环地址、IP 字面量、凭据和可改变目标的查询参数，
 * 避免生产启动校验与容器执行使用不同的地址语义。</p>
 */
public final class TrustedDependencyRegistry {

    private final String url;

    private TrustedDependencyRegistry(String url) {
        this.url = url;
    }

    public String url() {
        return url;
    }

    /** 解析并返回可直接注入包管理器的规范化地址。 */
    public static TrustedDependencyRegistry parse(String value) {
        if (value == null || value.isBlank() || containsControlCharacter(value)) {
            throw new IllegalArgumentException("生成代码依赖 registry 地址不安全");
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = normalize(uri.getScheme());
            String host = normalize(uri.getHost());
            if (!("http".equals(scheme) || "https".equals(scheme))
                    || !isTrustedDnsName(host)
                    || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65_535
                    || hasUnsafePath(uri.getPath())) {
                throw new IllegalArgumentException("生成代码依赖 registry 地址不安全");
            }
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                path = "/";
            } else if (!path.endsWith("/")) {
                path += "/";
            }
            URI normalized = new URI(
                    scheme,
                    null,
                    host,
                    uri.getPort(),
                    path,
                    null,
                    null
            );
            return new TrustedDependencyRegistry(normalized.toASCIIString());
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("生成代码依赖 registry 地址不安全", exception);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isTrustedDnsName(String host) {
        if (host.isBlank()
                || host.length() > 253
                || "localhost".equals(host)
                || host.endsWith(".localhost")
                || host.startsWith(".")
                || host.endsWith(".")
                || host.contains("..")
                || host.contains(":")
                || host.matches("[0-9.]+")) {
            return false;
        }
        for (String label : host.split("\\.")) {
            if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUnsafePath(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return false;
        }
        if (!path.startsWith("/") || path.contains("//") || path.contains("\\")) {
            return true;
        }
        for (String segment : path.substring(1).split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsControlCharacter(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }
}
