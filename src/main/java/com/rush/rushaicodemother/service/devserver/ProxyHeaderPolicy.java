package com.rush.rushaicodemother.service.devserver;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Dev Server 代理头策略。
 * 阻止认证凭据、Cookie、逐跳头和上游 Set-Cookie 穿透代理边界。
 */
@Component
public class ProxyHeaderPolicy {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private static final Set<String> BLOCKED_REQUEST_HEADERS = Set.of(
            "authorization",
            "cookie",
            "host",
            "content-length",
            "expect",
            "forwarded",
            "x-forwarded-for",
            "x-forwarded-host",
            "x-forwarded-proto"
    );

    private static final Set<String> BLOCKED_RESPONSE_HEADERS = Set.of(
            "set-cookie",
            "set-cookie2",
            "content-length"
    );

    public boolean shouldForwardRequestHeader(String headerName) {
        String normalizedName = normalize(headerName);
        return !normalizedName.isEmpty()
                && !HOP_BY_HOP_HEADERS.contains(normalizedName)
                && !BLOCKED_REQUEST_HEADERS.contains(normalizedName)
                && !normalizedName.startsWith(DevServerInternalRequestSigner.HEADER_PREFIX)
                && !normalizedName.startsWith("proxy-");
    }

    public boolean shouldForwardResponseHeader(String headerName) {
        String normalizedName = normalize(headerName);
        return !normalizedName.isEmpty()
                && !HOP_BY_HOP_HEADERS.contains(normalizedName)
                && !BLOCKED_RESPONSE_HEADERS.contains(normalizedName)
                && !normalizedName.startsWith("proxy-");
    }

    private String normalize(String headerName) {
        return headerName == null ? "" : headerName.toLowerCase(Locale.ROOT);
    }
}
