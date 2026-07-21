package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerInternalRoutingProperties;
import com.rush.rushaicodemother.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DevServerInternalRequestSignerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final byte[] BODY = "payload".getBytes(StandardCharsets.UTF_8);

    @Test
    void shouldVerifyMethodPathQueryAndBodyAndRejectReplay() {
        DevServerInternalRequestSigner signer = signer(NOW, "nonce-00000001");
        URI target = URI.create(
                "http://preview-node-b:8123/api/internal/dev-server/proxy/21/resource?mode=edit"
        );
        Map<String, String> headers = signer.sign("PATCH", target, BODY);
        MockHttpServletRequest request = request("PATCH", target, headers);

        VerifiedDevServerInternalRequest verified = signer.verify(request);
        signer.verifyBody(verified, BODY);

        assertEquals("preview-node-a", verified.sourceNode());
        assertThrows(BusinessException.class, () -> signer.verify(request));
    }

    @Test
    void shouldRejectPathAndBodyTampering() {
        DevServerInternalRequestSigner signer = signer(NOW, "nonce-00000002");
        URI target = URI.create("http://preview-node-b:8123/api/internal/dev-server/proxy/21/resource");
        Map<String, String> headers = signer.sign("POST", target, BODY);

        MockHttpServletRequest pathTampered = request(
                "POST",
                URI.create("http://preview-node-b:8123/api/internal/dev-server/proxy/21/admin"),
                headers
        );
        assertThrows(BusinessException.class, () -> signer.verify(pathTampered));

        DevServerInternalRequestSigner bodyVerifier = signer(NOW, "nonce-00000003");
        Map<String, String> bodyHeaders = bodyVerifier.sign("POST", target, BODY);
        VerifiedDevServerInternalRequest verified = bodyVerifier.verify(request("POST", target, bodyHeaders));
        assertThrows(BusinessException.class,
                () -> bodyVerifier.verifyBody(verified, "changed".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldRejectExpiredSignaturesAndMissingConfiguration() {
        DevServerInternalRequestSigner sender = signer(NOW, "nonce-00000004");
        DevServerInternalRequestSigner receiver = signer(NOW.plusSeconds(31), "nonce-00000005");
        URI target = URI.create("http://preview-node-b:8123/api/internal/dev-server/proxy/21/");
        Map<String, String> headers = sender.sign("GET", target, new byte[0]);

        assertThrows(BusinessException.class,
                () -> receiver.verify(request("GET", target, headers)));

        DevServerInternalRoutingProperties properties = new DevServerInternalRoutingProperties();
        assertThrows(BusinessException.class,
                () -> signer(properties, NOW, "nonce-00000006")
                        .sign("GET", target, new byte[0]));
    }

    private DevServerInternalRequestSigner signer(Instant instant, String nonce) {
        DevServerInternalRoutingProperties properties = new DevServerInternalRoutingProperties();
        properties.setSharedSecret("0123456789abcdef0123456789abcdef");
        return signer(properties, instant, nonce);
    }

    private DevServerInternalRequestSigner signer(
            DevServerInternalRoutingProperties properties,
            Instant instant,
            String nonce
    ) {
        DevServerNodeIdentityProvider identityProvider = mock(DevServerNodeIdentityProvider.class);
        when(identityProvider.nodeId()).thenReturn("preview-node-a");
        return new DevServerInternalRequestSigner(
                properties,
                identityProvider,
                Clock.fixed(instant, ZoneOffset.UTC),
                () -> nonce
        );
    }

    private MockHttpServletRequest request(
            String method,
            URI target,
            Map<String, String> headers
    ) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, target.getRawPath());
        request.setQueryString(target.getRawQuery());
        headers.forEach(request::addHeader);
        return request;
    }
}
