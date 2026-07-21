package com.rush.rushaicodemother.controller.app.websocket;

import com.rush.rushaicodemother.application.app.AppDevServerApplicationService;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.service.UserService;
import com.rush.rushaicodemother.service.devserver.DevServerInternalRequestSigner;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPathFactory;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewRoute;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewTargetResolver;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketProxyHandler;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketUpstream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PublicDevServerWebSocketHandshakeInterceptorTest {

    private UserService userService;
    private AppDevServerApplicationService applicationService;
    private DevServerInternalRequestSigner signer;
    private PublicDevServerWebSocketHandshakeInterceptor interceptor;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        applicationService = mock(AppDevServerApplicationService.class);
        signer = mock(DevServerInternalRequestSigner.class);
        ServerProperties serverProperties = new ServerProperties();
        serverProperties.getServlet().setContextPath("/api");
        interceptor = new PublicDevServerWebSocketHandshakeInterceptor(
                new DevServerWebSocketRequestParser(),
                userService,
                applicationService,
                new DevServerPreviewTargetResolver(new DevServerPreviewPathFactory(serverProperties)),
                signer
        );
    }

    @Test
    void localHandshakeMustAuthorizeTheOwnerAndRetainNoBrowserCredentials() {
        MockHttpServletRequest servletRequest = request();
        servletRequest.addHeader("Cookie", "session=browser-secret");
        servletRequest.addHeader("Authorization", "Bearer browser-secret");
        User actor = User.builder().id(7L).build();
        when(userService.getLoginUser(servletRequest)).thenReturn(actor);
        when(applicationService.requireProxyRoute(21L, actor))
                .thenReturn(DevServerPreviewRoute.local(21L, "preview-node-a", 5180));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(accepted);
        DevServerWebSocketUpstream upstream = (DevServerWebSocketUpstream) attributes.get(
                DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE);
        assertEquals(
                "ws://127.0.0.1:5180/api/app/dev-server/proxy/21/?token=abc",
                upstream.targetUri().toString()
        );
        assertTrue(upstream.headers().isEmpty());
        verifyNoInteractions(signer);
    }

    @Test
    void remoteHandshakeMustUseOnlyServerGeneratedInternalHeaders() {
        MockHttpServletRequest servletRequest = request();
        User actor = User.builder().id(7L).build();
        when(userService.getLoginUser(servletRequest)).thenReturn(actor);
        when(applicationService.requireProxyRoute(21L, actor)).thenReturn(
                DevServerPreviewRoute.remote(
                        21L,
                        "preview-node-b",
                        5180,
                        URI.create("http://preview-node-b:8123/api")
                )
        );
        URI target = URI.create(
                "ws://preview-node-b:8123/api/internal/dev-server/proxy/21/?token=abc"
        );
        when(signer.sign(eq("GET"), eq(target), any(byte[].class)))
                .thenReturn(Map.of(DevServerInternalRequestSigner.SIGNATURE_HEADER, "trusted"));
        Map<String, Object> attributes = new HashMap<>();

        boolean accepted = interceptor.beforeHandshake(
                new ServletServerHttpRequest(servletRequest),
                new ServletServerHttpResponse(new MockHttpServletResponse()),
                mock(WebSocketHandler.class),
                attributes
        );

        assertTrue(accepted);
        DevServerWebSocketUpstream upstream = (DevServerWebSocketUpstream) attributes.get(
                DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE);
        assertEquals(target, upstream.targetUri());
        assertEquals(Map.of(DevServerInternalRequestSigner.SIGNATURE_HEADER, "trusted"),
                upstream.headers());
    }

    @Test
    void unauthorizedHandshakeMustBeRejectedBeforeUpstreamSelection() {
        MockHttpServletRequest servletRequest = request();
        User actor = User.builder().id(7L).build();
        when(userService.getLoginUser(servletRequest)).thenReturn(actor);
        when(applicationService.requireProxyRoute(21L, actor))
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
        verifyNoInteractions(signer);
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/app/dev-server/proxy/21/"
        );
        request.setContextPath("/api");
        request.setQueryString("token=abc");
        return request;
    }
}
