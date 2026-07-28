package com.rush.rushaicodemother.service.browser;

import java.net.URI;
import java.util.Locale;

/** 用于可以检查生成的应用程序的浏览器自动化的共享 SSRF 边界。 */
public final class LoopbackBrowserTargetPolicy {

    private LoopbackBrowserTargetPolicy() {
    }

    /**
 * 校验并返回有效的{@code Allowed}。
 *
 * @param candidate 候选
 * @return {@code Allowed}
 */
    public static URI requireAllowed(URI candidate) {
        if (!isAllowed(candidate)) {
            throw new IllegalArgumentException("browser automation target must be a normalized loopback HTTP URI");
        }
        return candidate.normalize();
    }

    /**
 * 判断{@code Allowed}是否满足约束。
 *
 * @param candidate 候选
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public static boolean isAllowed(URI candidate) {
        if (candidate == null
                || !candidate.isAbsolute()
                || !"http".equalsIgnoreCase(candidate.getScheme())
                || candidate.getUserInfo() != null
                || candidate.getHost() == null
                || candidate.getPort() == 0
                || candidate.getPort() > 65_535
                || !candidate.normalize().equals(candidate)) {
            return false;
        }
        String host = normalizedHost(candidate);
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host)
                || "::1".equals(host);
        if (!loopback) {
            return false;
        }
        String rawPath = candidate.getRawPath();
        if (rawPath == null || rawPath.indexOf('\\') >= 0) {
            return false;
        }
        String normalizedPath = rawPath.toLowerCase(Locale.ROOT);
        return !normalizedPath.contains("%2e")
                && !normalizedPath.contains("%2f")
                && !normalizedPath.contains("%5c");
    }

    /**
 * 返回{@code same}来源。
 *
 * @param left {@code left} 对应的调用参数
 * @param right {@code right} 对应的调用参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public static boolean sameOrigin(URI left, URI right) {
        return isAllowed(left)
                && isAllowed(right)
                && left.getScheme().equalsIgnoreCase(right.getScheme())
                && normalizedHost(left).equalsIgnoreCase(normalizedHost(right))
                && effectivePort(left) == effectivePort(right);
    }

    private static String normalizedHost(URI uri) {
        String host = uri.getHost();
        return host != null && host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0 ? 80 : uri.getPort();
    }
}
