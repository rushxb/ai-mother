package com.rush.rushaicodemother.service.devserver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxyHeaderPolicyTest {

    private final ProxyHeaderPolicy policy = new ProxyHeaderPolicy();

    @Test
    void requestPolicyMustBlockCredentialsAndHopByHopHeaders() {
        assertFalse(policy.shouldForwardRequestHeader("Authorization"));
        assertFalse(policy.shouldForwardRequestHeader("Cookie"));
        assertFalse(policy.shouldForwardRequestHeader("Proxy-Authorization"));
        assertFalse(policy.shouldForwardRequestHeader("Connection"));
        assertFalse(policy.shouldForwardRequestHeader("X-Forwarded-For"));
        assertTrue(policy.shouldForwardRequestHeader("Content-Type"));
        assertTrue(policy.shouldForwardRequestHeader("Accept"));
    }

    @Test
    void responsePolicyMustBlockCookiesAndHopByHopHeaders() {
        assertFalse(policy.shouldForwardResponseHeader("Set-Cookie"));
        assertFalse(policy.shouldForwardResponseHeader("Transfer-Encoding"));
        assertFalse(policy.shouldForwardResponseHeader("Content-Length"));
        assertTrue(policy.shouldForwardResponseHeader("Content-Type"));
        assertTrue(policy.shouldForwardResponseHeader("Cache-Control"));
    }
}
