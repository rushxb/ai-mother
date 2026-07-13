package com.rush.rushaicodemother.ratelimiter.ip;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 从 HTTP 请求中解析可用于安全策略的客户端 IP。
 */
public interface ClientIpResolver {

    String resolve(HttpServletRequest request);
}
