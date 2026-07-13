package com.rush.rushaicodemother.ratelimiter.ip;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.ratelimiter.config.RateLimiterProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * 仅在直接上游属于可信代理时解析转发头。
 *
 * <p>{@code X-Forwarded-For} 从右向左按可信代理链回溯，避免客户端在左侧注入伪造地址。
 * 转发头格式异常、重复或超限时回退到直接上游地址，确保限流失败关闭。</p>
 */
@Component
public class TrustedProxyClientIpResolver implements ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final String REAL_IP_HEADER = "X-Real-IP";

    private final List<CidrBlock> trustedProxies;
    private final int maximumHeaderLength;
    private final int maximumForwardedHops;

    public TrustedProxyClientIpResolver(RateLimiterProperties properties) {
        this.trustedProxies = properties.getTrustedProxies().stream()
                .map(CidrBlock::parse)
                .toList();
        this.maximumHeaderLength = properties.getForwardedHeaderMaxLength();
        this.maximumForwardedHops = properties.getForwardedForMaxHops();
    }

    @Override
    public String resolve(HttpServletRequest request) {
        if (request == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "限流器无法获取 HTTP 请求");
        }
        IpAddressParser.ParsedIpAddress directPeer = IpAddressParser.parse(request.getRemoteAddr())
                .orElseThrow(() -> new BusinessException(ErrorCode.SYSTEM_ERROR, "请求来源 IP 无效"));
        if (!isTrustedProxy(directPeer)) {
            return directPeer.normalizedValue();
        }

        HeaderValue forwardedFor = readSingleHeader(request, FORWARDED_FOR_HEADER);
        if (!forwardedFor.valid()) {
            return directPeer.normalizedValue();
        }
        if (forwardedFor.present()) {
            return resolveForwardedChain(forwardedFor.value(), directPeer)
                    .map(IpAddressParser.ParsedIpAddress::normalizedValue)
                    .orElse(directPeer.normalizedValue());
        }

        HeaderValue realIp = readSingleHeader(request, REAL_IP_HEADER);
        if (!realIp.valid()) {
            return directPeer.normalizedValue();
        }
        return realIp.present()
                ? IpAddressParser.parse(realIp.value())
                .map(IpAddressParser.ParsedIpAddress::normalizedValue)
                .orElse(directPeer.normalizedValue())
                : directPeer.normalizedValue();
    }

    private Optional<IpAddressParser.ParsedIpAddress> resolveForwardedChain(
            String headerValue,
            IpAddressParser.ParsedIpAddress directPeer
    ) {
        String[] hops = headerValue.split(",", -1);
        if (hops.length == 0 || hops.length > maximumForwardedHops) {
            return Optional.empty();
        }

        IpAddressParser.ParsedIpAddress current = directPeer;
        for (int index = hops.length - 1; index >= 0; index--) {
            if (!isTrustedProxy(current)) {
                break;
            }
            Optional<IpAddressParser.ParsedIpAddress> candidate = IpAddressParser.parse(hops[index]);
            if (candidate.isEmpty()) {
                return Optional.empty();
            }
            current = candidate.orElseThrow();
        }
        return Optional.of(current);
    }

    private boolean isTrustedProxy(IpAddressParser.ParsedIpAddress address) {
        return trustedProxies.stream().anyMatch(cidr -> cidr.contains(address));
    }

    private HeaderValue readSingleHeader(HttpServletRequest request, String headerName) {
        Enumeration<String> values = request.getHeaders(headerName);
        if (values == null || !values.hasMoreElements()) {
            return HeaderValue.absent();
        }
        String value = values.nextElement();
        if (values.hasMoreElements() || value == null || value.length() > maximumHeaderLength) {
            return HeaderValue.invalid();
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return HeaderValue.invalid();
        }
        return HeaderValue.present(trimmed);
    }

    private record HeaderValue(boolean present, boolean valid, String value) {

        private static HeaderValue absent() {
            return new HeaderValue(false, true, null);
        }

        private static HeaderValue invalid() {
            return new HeaderValue(true, false, null);
        }

        private static HeaderValue present(String value) {
            return new HeaderValue(true, true, value);
        }
    }
}
