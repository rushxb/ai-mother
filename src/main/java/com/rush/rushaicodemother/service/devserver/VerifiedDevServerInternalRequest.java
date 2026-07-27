package com.rush.rushaicodemother.service.devserver;

/** 经过身份验证的元数据将保留，直到验证代理的请求正文。 */
public record VerifiedDevServerInternalRequest(
        String sourceNode,
        String nonce,
        String expectedBodySha256
) {
}
