package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerProxyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerProxyServiceTest {

    @Test
    void shouldRejectOversizedRequestBeforeCallingUpstream() throws Exception {
        DevServerProxyProperties properties = new DevServerProxyProperties();
        properties.setMaxRequestBody(DataSize.ofBytes(4));
        HttpClient httpClient = mock(HttpClient.class);
        DevServerInternalRequestSigner requestSigner = mock(DevServerInternalRequestSigner.class);
        DevServerProxyService service = new DevServerProxyService(
                properties,
                new ProxyHeaderPolicy(),
                requestSigner,
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory("/api")),
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(
                DevServerPreviewRoute.local(21L, "preview-node-a", 5173),
                "/upload",
                null,
                request,
                response
        );

        assertEquals(413, response.getStatus());
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldProxyPatchBodyWithoutForwardingCredentialsOrSetCookie() throws Exception {
        DevServerProxyProperties properties = new DevServerProxyProperties();
        HttpClient httpClient = mock(HttpClient.class);
        DevServerInternalRequestSigner requestSigner = mock(DevServerInternalRequestSigner.class);
        HttpResponse upstreamResponse = mock(HttpResponse.class);
        when(upstreamResponse.statusCode()).thenReturn(200);
        when(upstreamResponse.body()).thenReturn(new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8)));
        when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of(
                        "Content-Type", List.of("text/plain"),
                        "Set-Cookie", List.of("session=upstream-secret")
                ),
                (name, value) -> true
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upstreamResponse);
        DevServerProxyService service = new DevServerProxyService(
                properties,
                new ProxyHeaderPolicy(),
                requestSigner,
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory("/api")),
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/resource");
        request.setContent("payload".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "session=backend-secret");
        request.addHeader("Content-Type", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(
                DevServerPreviewRoute.local(21L, "preview-node-a", 5173),
                "/resource",
                "mode=edit",
                request,
                response
        );

        assertEquals(200, response.getStatus());
        assertEquals("ok", response.getContentAsString());
        assertFalse(response.containsHeader("Set-Cookie"));
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest upstreamRequest = requestCaptor.getValue();
        assertEquals("PATCH", upstreamRequest.method());
        assertEquals(
                "http://127.0.0.1:5173/api/app/dev-server/proxy/21/resource?mode=edit",
                upstreamRequest.uri().toString()
        );
        assertTrue(upstreamRequest.headers().firstValue("Content-Type").isPresent());
        assertTrue(upstreamRequest.headers().firstValue("Authorization").isEmpty());
        assertTrue(upstreamRequest.headers().firstValue("Cookie").isEmpty());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void previewMustBeSandboxedAndOverrideUpstreamContentSecurityPolicy() throws Exception {
        DevServerProxyProperties properties = new DevServerProxyProperties();
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse upstreamResponse = mock(HttpResponse.class);
        when(upstreamResponse.statusCode()).thenReturn(200);
        when(upstreamResponse.body()).thenReturn(
                new ByteArrayInputStream("<html></html>".getBytes(StandardCharsets.UTF_8)));
        // 用户的 vite 配置可能自行下发宽松 CSP，平台策略必须覆盖而不是叠加。
        when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(
                Map.of(
                        "Content-Type", List.of("text/html"),
                        "Content-Security-Policy", List.of("default-src *")
                ),
                (name, value) -> true
        ));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upstreamResponse);
        DevServerProxyService service = new DevServerProxyService(
                properties,
                new ProxyHeaderPolicy(),
                mock(DevServerInternalRequestSigner.class),
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory("/api")),
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/index.html");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(
                DevServerPreviewRoute.local(21L, "preview-node-a", 5173),
                "/index.html",
                null,
                request,
                response
        );

        List<String> policies = response.getHeaders("Content-Security-Policy");
        assertEquals(1, policies.size(), "平台必须是 CSP 的唯一来源，不得与上游策略叠加");
        String policy = policies.getFirst();
        assertFalse(policy.contains("default-src *"), "上游宽松策略必须被覆盖");
        assertTrue(policy.contains("sandbox "), "预览产物必须启用 sandbox");
        assertFalse(policy.contains("allow-same-origin"), "预览产物不得获得同源权限");
        // 预览需要保留 Vite HMR，因此按 scheme 放通 WebSocket，但仍不允许 HTTP 外发。
        assertTrue(policy.contains("connect-src ws: wss:"), "预览必须保留 HMR WebSocket 通道");
        assertEquals("SAMEORIGIN", response.getHeader("X-Frame-Options"));
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void remoteRouteMustUseServerGeneratedSignatureAndDropForgedInternalHeaders() throws Exception {
        DevServerProxyProperties properties = new DevServerProxyProperties();
        HttpClient httpClient = mock(HttpClient.class);
        DevServerInternalRequestSigner requestSigner = mock(DevServerInternalRequestSigner.class);
        HttpResponse upstreamResponse = mock(HttpResponse.class);
        when(upstreamResponse.statusCode()).thenReturn(200);
        when(upstreamResponse.body()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(upstreamResponse.headers()).thenReturn(java.net.http.HttpHeaders.of(Map.of(), (name, value) -> true));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(upstreamResponse);
        URI signedTarget = URI.create(
                "http://preview-node-b:8123/api/internal/dev-server/proxy/21/@vite/client?token=abc"
        );
        when(requestSigner.sign(eq("GET"), eq(signedTarget), any(byte[].class)))
                .thenReturn(Map.of(
                        DevServerInternalRequestSigner.SOURCE_NODE_HEADER, "preview-node-a",
                        DevServerInternalRequestSigner.SIGNATURE_HEADER, "trusted-signature"
                ));
        DevServerProxyService service = new DevServerProxyService(
                properties,
                new ProxyHeaderPolicy(),
                requestSigner,
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory("/api")),
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/@vite/client");
        request.addHeader("Authorization", "Bearer browser-secret");
        request.addHeader("Cookie", "session=browser-secret");
        request.addHeader(DevServerInternalRequestSigner.SOURCE_NODE_HEADER, "attacker-node");
        request.addHeader(DevServerInternalRequestSigner.SIGNATURE_HEADER, "forged-signature");
        MockHttpServletResponse response = new MockHttpServletResponse();
        DevServerPreviewRoute route = DevServerPreviewRoute.remote(
                21L,
                "preview-node-b",
                5180,
                URI.create("http://preview-node-b:8123/api")
        );

        service.proxy(route, "/@vite/client", "token=abc", request, response);

        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest upstreamRequest = requestCaptor.getValue();
        assertEquals(signedTarget, upstreamRequest.uri());
        assertEquals("preview-node-a", upstreamRequest.headers()
                .firstValue(DevServerInternalRequestSigner.SOURCE_NODE_HEADER).orElseThrow());
        assertEquals("trusted-signature", upstreamRequest.headers()
                .firstValue(DevServerInternalRequestSigner.SIGNATURE_HEADER).orElseThrow());
        assertTrue(upstreamRequest.headers().firstValue("Authorization").isEmpty());
        assertTrue(upstreamRequest.headers().firstValue("Cookie").isEmpty());
    }
}
