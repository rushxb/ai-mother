package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerProxyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
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
        DevServerProxyService service = new DevServerProxyService(
                properties,
                new ProxyHeaderPolicy(),
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/upload");
        request.setContent("12345".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(5173, "/upload", null, request, response);

        assertEquals(413, response.getStatus());
        verify(httpClient, never()).send(any(), any());
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void shouldProxyPatchBodyWithoutForwardingCredentialsOrSetCookie() throws Exception {
        DevServerProxyProperties properties = new DevServerProxyProperties();
        HttpClient httpClient = mock(HttpClient.class);
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
                httpClient
        );
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/resource");
        request.setContent("payload".getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer secret");
        request.addHeader("Cookie", "session=backend-secret");
        request.addHeader("Content-Type", "application/json");
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.proxy(5173, "/resource", "mode=edit", request, response);

        assertEquals(200, response.getStatus());
        assertEquals("ok", response.getContentAsString());
        assertFalse(response.containsHeader("Set-Cookie"));
        ArgumentCaptor<HttpRequest> requestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest upstreamRequest = requestCaptor.getValue();
        assertEquals("PATCH", upstreamRequest.method());
        assertTrue(upstreamRequest.uri().toString().endsWith("/resource?mode=edit"));
        assertTrue(upstreamRequest.headers().firstValue("Content-Type").isPresent());
        assertTrue(upstreamRequest.headers().firstValue("Authorization").isEmpty());
        assertTrue(upstreamRequest.headers().firstValue("Cookie").isEmpty());
    }
}
