package com.rush.rushaicodemother.infrastructure.security;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.net.IDN;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * AI 模型出站目的地址策略。
 *
 * <p>配置保存、连接探测与真实模型调用必须复用该模块，避免任一调用点绕过
 * HTTPS、DNS 和非公网地址检查。</p>
 */
@Component
public final class AiModelOutboundDestinationPolicy {

    private static final List<String> LOCAL_HOST_SUFFIXES = List.of(
            "localhost", ".localhost", ".local", ".localdomain", ".internal", ".home.arpa"
    );

    private final HostAddressResolver addressResolver;

    public AiModelOutboundDestinationPolicy(HostAddressResolver addressResolver) {
        this.addressResolver = Objects.requireNonNull(addressResolver, "主机地址解析器不能为空");
    }

    /**
     * 校验并规范化模型基础地址。
     *
     * @return 已批准且不含尾部斜杠的 HTTPS 基础地址
     */
    public String normalizeAndValidateBaseUrl(String value) {
        String trimmed = StrUtil.trim(value);
        if (StrUtil.isBlank(trimmed)) {
            return "";
        }
        return approveBaseUrl(trimmed).baseUrl();
    }

    /** 返回可绑定到单个 HTTP 客户端的已批准目的地址。 */
    public ApprovedDestination approveBaseUrl(String value) {
        String trimmed = StrUtil.trim(value);
        if (StrUtil.isBlank(trimmed)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型 API 地址不能为空");
        }
        try {
            URI source = new URI(trimmed).normalize();
            String scheme = StrUtil.blankToDefault(source.getScheme(), "")
                    .toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) || StrUtil.isBlank(source.getHost())) {
                throw new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "AI 模型 API 地址必须是包含主机名的 HTTPS 地址"
                );
            }
            if (source.getUserInfo() != null || source.getQuery() != null
                    || source.getFragment() != null) {
                throw new BusinessException(
                        ErrorCode.PARAMS_ERROR,
                        "AI 模型 API 地址不能包含用户信息、查询参数或片段"
                );
            }
            int port = source.getPort();
            if (port == 0 || port > 65535) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型 API 地址端口不合法");
            }

            String host = normalizeHost(source.getHost());
            requireAllowedHost(host);
            URI normalized = new URI(
                    "https", null, host, port, source.getPath(), null, null).normalize();
            String baseUrl = StrUtil.removeSuffix(normalized.toString(), "/");
            return new ApprovedDestination(
                    URI.create(baseUrl), baseUrl, host, effectivePort("https", port));
        } catch (BusinessException exception) {
            throw exception;
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型 API 地址格式错误", exception);
        }
    }

    /**
     * 校验模型客户端即将发送的请求仍属于批准的 HTTPS 源与基础路径。
     * 每次请求都会重新解析 DNS；连接层随后还会用同一策略解析并固定实际地址。
     */
    public void requireAllowedRequest(ApprovedDestination approved, URI requestUri) {
        if (approved == null || requestUri == null) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 模型出站请求缺少批准目的地址");
        }
        try {
            URI normalized = requestUri.normalize();
            String scheme = StrUtil.blankToDefault(normalized.getScheme(), "")
                    .toLowerCase(Locale.ROOT);
            String host = normalizeHost(normalized.getHost());
            int port = effectivePort(scheme, normalized.getPort());
            if (!"https".equals(scheme) || normalized.getUserInfo() != null
                    || normalized.getFragment() != null
                    || !approved.host().equals(host) || approved.port() != port
                    || !isWithinBasePath(approved.baseUri().getPath(), normalized.getPath())) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "AI 模型出站请求偏离已批准的 API 地址"
                );
            }
            requireAllowedHost(host);
        } catch (BusinessException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "AI 模型出站请求地址不合法", exception);
        }
    }

    /** 目标协议、主机或端口变化时，禁止把旧密钥带到新的网络主体。 */
    public void requireReplacementSecretForDestinationChange(String currentBaseUrl,
                                                              String replacementBaseUrl,
                                                              String replacementApiKey) {
        if (replacementBaseUrl == null || StrUtil.isNotBlank(replacementApiKey)) {
            return;
        }
        if (!authorityOf(currentBaseUrl).equals(authorityOf(replacementBaseUrl))) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "修改 AI 模型 API 主机或端口时必须重新输入 API 密钥"
            );
        }
    }

    /** 官方目录模型只能访问登记过的 Provider 网络主体。 */
    public void requireAllowlistedAuthority(String candidateBaseUrl,
                                            List<String> allowlistedBaseUrls) {
        DestinationAuthority candidate = authorityOf(candidateBaseUrl);
        boolean allowed = allowlistedBaseUrls != null && allowlistedBaseUrls.stream()
                .filter(StrUtil::isNotBlank)
                .map(this::authorityOf)
                .anyMatch(candidate::equals);
        if (!allowed) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "官方目录模型只能使用已登记的 Provider API 主机"
            );
        }
    }

    /** 在连接前解析并校验全部地址；任一非公网地址都会拒绝整个目的主机。 */
    public InetAddress[] resolveAllowedAddresses(String host) throws UnknownHostException {
        String normalizedHost;
        try {
            normalizedHost = normalizeHost(host);
        } catch (IllegalArgumentException exception) {
            throw unknownHost("AI model destination host is invalid", exception);
        }
        if (isLocalHostname(normalizedHost)) {
            throw unknownHost("AI model destination resolves to a prohibited network", null);
        }

        InetAddress[] addresses = addressResolver.resolve(normalizedHost);
        if (addresses == null || addresses.length == 0) {
            throw unknownHost("AI model destination host has no address", null);
        }
        if (Arrays.stream(addresses).anyMatch(address -> !isPublicUnicast(address))) {
            throw unknownHost("AI model destination resolves to a prohibited network", null);
        }
        return addresses.clone();
    }

    private void requireAllowedHost(String host) {
        try {
            resolveAllowedAddresses(host);
        } catch (UnknownHostException exception) {
            throw new BusinessException(
                    ErrorCode.PARAMS_ERROR,
                    "AI 模型 API 地址不能指向本机、私网、链路本地或保留网络",
                    exception
            );
        }
    }

    private String normalizeHost(String host) {
        String normalized = StrUtil.trim(host);
        if (StrUtil.isBlank(normalized)) {
            throw new IllegalArgumentException("host is blank");
        }
        normalized = StrUtil.removeSuffix(normalized, ".");
        if (normalized.indexOf(':') >= 0) {
            return normalized.toLowerCase(Locale.ROOT);
        }
        return IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    }

    private DestinationAuthority authorityOf(String value) {
        String normalizedValue = StrUtil.trim(value);
        if (StrUtil.isBlank(normalizedValue)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型 API 地址不能为空");
        }
        try {
            URI uri = new URI(normalizedValue);
            String scheme = StrUtil.blankToDefault(uri.getScheme(), "")
                    .toLowerCase(Locale.ROOT);
            if (StrUtil.isBlank(scheme) || StrUtil.isBlank(uri.getHost())) {
                throw new IllegalArgumentException("destination authority is incomplete");
            }
            int port = effectivePort(scheme, uri.getPort());
            return new DestinationAuthority(scheme, normalizeHost(uri.getHost()), port);
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "AI 模型 API 地址格式错误", exception);
        }
    }

    private int effectivePort(String scheme, int configuredPort) {
        if (configuredPort >= 0) {
            return configuredPort;
        }
        return "https".equals(scheme) ? 443 : 80;
    }

    private boolean isWithinBasePath(String basePath, String requestPath) {
        String normalizedBase = StrUtil.removeSuffix(
                StrUtil.blankToDefault(basePath, ""), "/");
        String normalizedRequest = StrUtil.blankToDefault(requestPath, "");
        if (StrUtil.isBlank(normalizedBase)) {
            return normalizedRequest.startsWith("/");
        }
        return normalizedRequest.equals(normalizedBase)
                || normalizedRequest.startsWith(normalizedBase + "/");
    }

    private boolean isLocalHostname(String host) {
        return LOCAL_HOST_SUFFIXES.stream().anyMatch(suffix ->
                suffix.charAt(0) == '.' ? host.endsWith(suffix) : host.equals(suffix));
    }

    private boolean isPublicUnicast(InetAddress address) {
        if (address == null || address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPublicIpv4(bytes);
        }
        if (bytes.length == 16) {
            return isPublicIpv6(bytes);
        }
        return false;
    }

    private boolean isPublicIpv4(byte[] bytes) {
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        int third = Byte.toUnsignedInt(bytes[2]);

        if (first == 0 || first == 10 || first == 127 || first >= 224) {
            return false;
        }
        if (first == 100 && second >= 64 && second <= 127) {
            return false;
        }
        if (first == 169 && second == 254) {
            return false;
        }
        if (first == 172 && second >= 16 && second <= 31) {
            return false;
        }
        if (first == 192 && (second == 168
                || second == 0 && (third == 0 || third == 2))) {
            return false;
        }
        if (first == 192 && second == 88 && third == 99) {
            return false;
        }
        if (first == 198 && (second == 18 || second == 19
                || second == 51 && third == 100)) {
            return false;
        }
        return !(first == 203 && second == 0 && third == 113);
    }

    private boolean isPublicIpv6(byte[] bytes) {
        // 仅允许 2000::/3 全局单播，并排除文档、基准和地址转换网段。
        if ((Byte.toUnsignedInt(bytes[0]) & 0xE0) != 0x20) {
            return false;
        }
        if (matchesPrefix(bytes, new int[]{0x20, 0x01, 0x0d, 0xb8}, 32)
                || matchesPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x02, 0x00, 0x00}, 48)
                || matchesPrefix(bytes, new int[]{0x20, 0x01, 0x00, 0x00}, 32)
                || matchesPrefix(bytes, new int[]{0x20, 0x02}, 16)) {
            return false;
        }
        int fourthNibble = Byte.toUnsignedInt(bytes[3]) >>> 4;
        return !(bytes[0] == 0x20 && bytes[1] == 0x01
                && (fourthNibble == 0x1 || fourthNibble == 0x2));
    }

    private boolean matchesPrefix(byte[] address, int[] prefixBytes, int prefixBits) {
        int completeBytes = prefixBits / 8;
        for (int index = 0; index < completeBytes; index++) {
            if (Byte.toUnsignedInt(address[index]) != prefixBytes[index]) {
                return false;
            }
        }
        return true;
    }

    private UnknownHostException unknownHost(String message, Exception cause) {
        UnknownHostException exception = new UnknownHostException(message);
        if (cause != null) {
            exception.initCause(cause);
        }
        return exception;
    }

    private record DestinationAuthority(String scheme, String host, int port) {
    }

    /** 单个模型 HTTP 客户端可访问的不可变目的地址。 */
    public record ApprovedDestination(URI baseUri, String baseUrl, String host, int port) {
    }
}
