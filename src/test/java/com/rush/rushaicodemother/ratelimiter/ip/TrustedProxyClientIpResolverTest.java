package com.rush.rushaicodemother.ratelimiter.ip;

import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrustedProxyClientIpResolverTest {

    @Test
    void shouldIgnoreForwardingHeadersByDefault() {
        TrustedProxyClientIpResolver resolver = resolverWithTrustedProxies(List.of());
        MockHttpServletRequest request = requestFrom("198.51.100.10");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("X-Real-IP", "203.0.113.8");

        assertEquals("198.51.100.10", resolver.resolve(request));
    }

    @Test
    void shouldResolveClientBehindTrustedProxy() {
        TrustedProxyClientIpResolver resolver = resolverWithTrustedProxies(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = requestFrom("10.0.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.7");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    void shouldWalkTrustedProxyChainFromRightToLeft() {
        TrustedProxyClientIpResolver resolver = resolverWithTrustedProxies(List.of("10.0.0.0/8"));
        MockHttpServletRequest request = requestFrom("10.0.0.2");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7, 10.0.0.3");

        assertEquals("203.0.113.7", resolver.resolve(request));
    }

    @Test
    void shouldFallBackToDirectPeerForInvalidOrOversizedForwardingHeader() {
        RateLimiterProperties properties = properties(List.of("10.0.0.0/8"));
        properties.setForwardedHeaderMaxLength(256);
        TrustedProxyClientIpResolver resolver = new TrustedProxyClientIpResolver(properties);

        MockHttpServletRequest invalidRequest = requestFrom("10.0.0.2");
        invalidRequest.addHeader("X-Forwarded-For", "not-an-ip");
        MockHttpServletRequest oversizedRequest = requestFrom("10.0.0.2");
        oversizedRequest.addHeader("X-Forwarded-For", "1".repeat(257));

        assertEquals("10.0.0.2", resolver.resolve(invalidRequest));
        assertEquals("10.0.0.2", resolver.resolve(oversizedRequest));
    }

    @Test
    void shouldSupportIpv6TrustedProxyRanges() {
        TrustedProxyClientIpResolver resolver = resolverWithTrustedProxies(List.of("2001:db8::/32"));
        MockHttpServletRequest request = requestFrom("2001:db8::10");
        request.addHeader("X-Real-IP", "2001:db9::25");

        assertEquals("2001:db9:0:0:0:0:0:25", resolver.resolve(request));
    }

    @Test
    void shouldRejectInvalidTrustedProxyConfiguration() {
        RateLimiterProperties properties = properties(List.of("invalid-cidr"));

        assertThrows(IllegalArgumentException.class, () -> new TrustedProxyClientIpResolver(properties));
    }

    private TrustedProxyClientIpResolver resolverWithTrustedProxies(List<String> trustedProxies) {
        return new TrustedProxyClientIpResolver(properties(trustedProxies));
    }

    private RateLimiterProperties properties(List<String> trustedProxies) {
        RateLimiterProperties properties = new RateLimiterProperties();
        properties.setTrustedProxies(trustedProxies);
        return properties;
    }

    private MockHttpServletRequest requestFrom(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
