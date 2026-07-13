package com.rush.rushaicodemother.ratelimiter.enums;

/**
 * 限流键的隔离维度。
 */
public enum RateLimitType {

    /**
     * 按接口方法限流。
     */
    API,

    /**
     * 登录用户按用户 ID 限流，未登录请求回退到客户端 IP。
     */
    USER,

    /**
     * 按可信解析后的客户端 IP 限流。
     */
    IP
}
