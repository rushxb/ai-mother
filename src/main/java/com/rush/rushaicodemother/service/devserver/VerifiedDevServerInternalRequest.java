package com.rush.rushaicodemother.service.devserver;

/** Authenticated metadata retained until the proxied request body is verified. */
public record VerifiedDevServerInternalRequest(
        String sourceNode,
        String nonce,
        String expectedBodySha256
) {
}
