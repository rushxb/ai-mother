package com.rush.rushaicodemother.service.browser;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoopbackBrowserTargetPolicyTest {

    @Test
    void loopbackHttpTargetsMustBeAccepted() {
        assertTrue(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("http://127.0.0.1:5180/app/")));
        assertTrue(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("http://localhost:8123/api/static/app/")));
        assertTrue(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("http://[::1]:5180/")));
    }

    @Test
    void metadataExternalAndEncodedTraversalTargetsMustBeRejected() {
        assertFalse(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("http://169.254.169.254/latest/meta-data/")));
        assertFalse(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("https://127.0.0.1:5180/")));
        assertFalse(LoopbackBrowserTargetPolicy.isAllowed(
                URI.create("http://127.0.0.1:5180/%2e%2e/secret")));
        assertThrows(IllegalArgumentException.class, () ->
                LoopbackBrowserTargetPolicy.requireAllowed(
                        URI.create("http://example.com/")));
    }

    @Test
    void sameOriginMustIncludeEffectivePort() {
        assertTrue(LoopbackBrowserTargetPolicy.sameOrigin(
                URI.create("http://127.0.0.1:5180/"),
                URI.create("http://127.0.0.1:5180/dashboard")));
        assertFalse(LoopbackBrowserTargetPolicy.sameOrigin(
                URI.create("http://127.0.0.1:5180/"),
                URI.create("http://127.0.0.1:5181/")));
    }
}
