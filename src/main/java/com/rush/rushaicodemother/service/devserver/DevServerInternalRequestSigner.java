package com.rush.rushaicodemother.service.devserver;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.DevServerInternalRoutingProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** HMAC 对节点到节点预览代理跃点进行身份验证并拒绝有界重播尝试。 */
@Component
public class DevServerInternalRequestSigner {

    public static final String HEADER_PREFIX = "x-ai-preview-";
    public static final String VERSION_HEADER = "X-AI-Preview-Version";
    public static final String SOURCE_NODE_HEADER = "X-AI-Preview-Source-Node";
    public static final String TIMESTAMP_HEADER = "X-AI-Preview-Timestamp";
    public static final String NONCE_HEADER = "X-AI-Preview-Nonce";
    public static final String BODY_SHA256_HEADER = "X-AI-Preview-Content-SHA256";
    public static final String SIGNATURE_HEADER = "X-AI-Preview-Signature";

    private static final String SIGNATURE_VERSION = "1";
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SHA256_ALGORITHM = "SHA-256";
    private static final String SAFE_NODE_ID_PATTERN = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}";

    private final byte[] sharedSecret;
    private final String sourceNode;
    private final Duration allowedClockSkew;
    private final Clock clock;
    private final Supplier<String> nonceSupplier;
    private final Cache<String, Boolean> consumedNonces;

    @Autowired
    public DevServerInternalRequestSigner(
            DevServerInternalRoutingProperties properties,
            DevServerNodeIdentityProvider identityProvider
    ) {
        this(properties, identityProvider, Clock.systemUTC(), () -> UUID.randomUUID().toString());
    }

    DevServerInternalRequestSigner(
            DevServerInternalRoutingProperties properties,
            DevServerNodeIdentityProvider identityProvider,
            Clock clock,
            Supplier<String> nonceSupplier
    ) {
        this.sharedSecret = properties.hasSharedSecret()
                ? properties.getSharedSecret().trim().getBytes(StandardCharsets.UTF_8)
                : new byte[0];
        this.sourceNode = identityProvider.nodeId();
        this.allowedClockSkew = properties.getAllowedClockSkew();
        this.clock = clock;
        this.nonceSupplier = nonceSupplier;
        this.consumedNonces = Caffeine.newBuilder()
                .maximumSize(properties.getReplayCacheMaxEntries())
                .expireAfterWrite(allowedClockSkew.multipliedBy(2))
                .build();
    }

    public Map<String, String> sign(String method, URI targetUri, byte[] requestBody) {
        requireConfigured();
        String timestamp = String.valueOf(clock.instant().getEpochSecond());
        String nonce = normalizeNonce(nonceSupplier.get());
        String bodySha256 = sha256(requestBody == null ? new byte[0] : requestBody);
        String canonicalTarget = canonicalTarget(targetUri);
        String normalizedMethod = normalizeMethod(method);
        String signature = signature(canonical(
                sourceNode, normalizedMethod, canonicalTarget, timestamp, nonce, bodySha256));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(VERSION_HEADER, SIGNATURE_VERSION);
        headers.put(SOURCE_NODE_HEADER, sourceNode);
        headers.put(TIMESTAMP_HEADER, timestamp);
        headers.put(NONCE_HEADER, nonce);
        headers.put(BODY_SHA256_HEADER, bodySha256);
        headers.put(SIGNATURE_HEADER, signature);
        return Map.copyOf(headers);
    }

    public VerifiedDevServerInternalRequest verify(HttpServletRequest request) {
        requireConfigured();
        if (request == null) {
            throw denied();
        }
        String version = boundedHeader(request, VERSION_HEADER, 8);
        String source = boundedHeader(request, SOURCE_NODE_HEADER, 128);
        String timestamp = boundedHeader(request, TIMESTAMP_HEADER, 32);
        String nonce = boundedHeader(request, NONCE_HEADER, 128);
        String bodySha256 = boundedHeader(request, BODY_SHA256_HEADER, 64);
        String suppliedSignature = boundedHeader(request, SIGNATURE_HEADER, 128);
        if (!SIGNATURE_VERSION.equals(version)
                || !source.matches(SAFE_NODE_ID_PATTERN)
                || !nonce.matches("[A-Za-z0-9_-]{8,128}")
                || !bodySha256.matches("[a-f0-9]{64}")) {
            throw denied();
        }

        Instant signedAt;
        try {
            signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (RuntimeException invalidTimestamp) {
            throw denied();
        }
        Duration clockDifference = Duration.between(signedAt, clock.instant()).abs();
        if (clockDifference.compareTo(allowedClockSkew) > 0) {
            throw denied();
        }

        String expectedSignature = signature(canonical(
                source,
                normalizeMethod(request.getMethod()),
                canonicalTarget(request),
                timestamp,
                nonce,
                bodySha256
        ));
        if (!MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                suppliedSignature.getBytes(StandardCharsets.US_ASCII))) {
            throw denied();
        }
        if (consumedNonces.asMap().putIfAbsent(source + ":" + nonce, Boolean.TRUE) != null) {
            throw denied();
        }
        return new VerifiedDevServerInternalRequest(source, nonce, bodySha256);
    }

    public void verifyBody(VerifiedDevServerInternalRequest verifiedRequest, byte[] requestBody) {
        if (verifiedRequest == null) {
            throw denied();
        }
        String actual = sha256(requestBody == null ? new byte[0] : requestBody);
        if (!MessageDigest.isEqual(
                actual.getBytes(StandardCharsets.US_ASCII),
                verifiedRequest.expectedBodySha256().getBytes(StandardCharsets.US_ASCII))) {
            throw denied();
        }
    }

    private String canonical(
            String source,
            String method,
            String target,
            String timestamp,
            String nonce,
            String bodySha256
    ) {
        return String.join("\n",
                SIGNATURE_VERSION, source, method, target, timestamp, nonce, bodySha256);
    }

    private String canonicalTarget(URI uri) {
        if (uri == null || uri.getRawPath() == null || uri.getRawPath().isBlank()) {
            throw new IllegalArgumentException("internal Preview target URI is invalid");
        }
        return uri.getRawQuery() == null
                ? uri.getRawPath()
                : uri.getRawPath() + "?" + uri.getRawQuery();
    }

    private String canonicalTarget(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        if (requestUri == null || requestUri.isBlank()) {
            throw denied();
        }
        String query = request.getQueryString();
        return query == null || query.isBlank() ? requestUri : requestUri + "?" + query;
    }

    private String signature(String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(sharedSecret, HMAC_ALGORITHM));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception cryptographicFailure) {
            throw new IllegalStateException("internal Preview request signing failed", cryptographicFailure);
        }
    }

    private String sha256(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance(SHA256_ALGORITHM).digest(value);
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception cryptographicFailure) {
            throw new IllegalStateException("internal Preview body hashing failed", cryptographicFailure);
        }
    }

    private String normalizeMethod(String method) {
        if (method == null || !method.matches("[A-Za-z]{3,10}")) {
            throw denied();
        }
        return method.toUpperCase(Locale.ROOT);
    }

    private String normalizeNonce(String nonce) {
        String normalized = nonce == null ? "" : nonce.replace("-", "").trim();
        if (!normalized.matches("[A-Za-z0-9_-]{8,128}")) {
            throw new IllegalStateException("internal Preview nonce supplier returned an invalid value");
        }
        return normalized;
    }

    private String boundedHeader(HttpServletRequest request, String name, int maxLength) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw denied();
        }
        return value.trim();
    }

    private void requireConfigured() {
        if (sharedSecret.length < 32) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "Internal Preview routing is not configured"
            );
        }
    }

    private BusinessException denied() {
        return new BusinessException(ErrorCode.NO_AUTH_ERROR, "Invalid internal Preview request");
    }
}
