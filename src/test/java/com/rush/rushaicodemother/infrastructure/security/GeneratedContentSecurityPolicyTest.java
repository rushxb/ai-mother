package com.rush.rushaicodemother.infrastructure.security;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GeneratedContentSecurityPolicyTest {

    @Test
    void previewPolicyMustAuthorizeOnlyThePublicResponseOrigin() {
        String connectSource = Arrays.stream(
                        GeneratedContentSecurityPolicy.PREVIEW_CONTENT_SECURITY_POLICY.split(";"))
                .map(String::trim)
                .filter(directive -> directive.startsWith("connect-src "))
                .findFirst()
                .orElseThrow();

        assertEquals("connect-src 'self'", connectSource);
        assertFalse(connectSource.contains("ws:"));
        assertFalse(connectSource.contains("wss:"));
    }
}
