package com.rush.rushaicodemother.controller.app.websocket;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPathFactory;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoutingService;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewTargetResolver;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketProxyHandler;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketUpstream;
import com.rush.rushaicodemother.service.devserver.VerifiedDevServerInternalRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InternalDevServerWebSocketHandshakeInterceptorTest {

    private DevServerInternalRequestSigner signer;
    private DevServerPreviewRoutingService routingService;
    private InternalDevServerWebSocketHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        signer = mock(DevServerInternalRequestSigner.class);
        routingService = mock(DevServerPreviewRoutingService.class);
        ServerProperties serverProperties = new ServerProperties();
        serverProperties.getServlet().setContextPath("/api");
        interceptor = new InternalDevServerWebSocketHandshakeInterceptor(
                new DevServerWebSocketRequestParser(),
                signer,
                routingService,
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory(serverProperties))
        );
    }

    @Test
    void signedHandshakeMustVerifyEmptyBodyAndFenceTheLocalOwner() {
        MockHttpServletRequest servletRequest = request();
        VerifiedDevServerInternalRequest verified = new VerifiedDevServerInternalRequest(
                "preview-node-a", "nonce-00000001", "0".repeat(64));
        when(signer.verify(servletRequest)).thenReturn(verified);
        when(routingService.requireLocalRunningPort(21L)).thenReturn(5180);
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(accepted);
        verify(signer).verifyBody(verified, new byte[0]);
        DevServerWebSocketUpstream upstream = (DevServerWebSocketUpstream) attributes.get(
                DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE);
        assertEquals(
                "ws://127.0.0.1:5180/api/app/dev-server/proxy/21/?token=abc",
                upstream.targetUri().toString()
        );
        assertEquals(Map.of(), upstream.headers());
    }

    @Test
    void invalidSignatureMustBeRejectedBeforeDurableOwnerLookup() {
        MockHttpServletRequest servletRequest = request();
        when(signer.verify(servletRequest))
                .thenThrow(new BusinessException(ErrorCode.NO_AUTH_ERROR));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(servletResponse),
                mock(WebSocketHandler.class),
                new HashMap<>()
        );

        assertFalse(accepted);
        assertEquals(403, servletResponse.getStatus());
        verify(routingService, never()).requireLocalRunningPort(21L);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/internal/dev-server/proxy/21/"
        );
        request.setContextPath("/api");
        request.setQueryString("token=abc");
        return request;
    }
}
