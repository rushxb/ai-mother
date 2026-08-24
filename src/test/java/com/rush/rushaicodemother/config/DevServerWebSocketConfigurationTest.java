package com.rush.rushaicodemother.config;

import com.rush.rushaicodemother.controller.app.websocket.InternalDevServerWebSocketHandshakeInterceptor;
import com.rush.rushaicodemother.controller.app.websocket.PublicDevServerWebSocketHandshakeInterceptor;
import com.rush.rushaicodemother.service.devserver.DevServerPreviewPaths;
import com.rush.rushaicodemother.service.devserver.DevServerWebSocketProxyHandler;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerWebSocketConfigurationTest {

    @Test
    void publicPreviewMustAcceptSandboxOriginWithoutRelaxingInternalEndpoint() {
        DevServerWebSocketProxyHandler handler = mock(DevServerWebSocketProxyHandler.class);
        PublicDevServerWebSocketHandshakeInterceptor publicInterceptor =
                mock(PublicDevServerWebSocketHandshakeInterceptor.class);
        InternalDevServerWebSocketHandshakeInterceptor internalInterceptor =
                mock(InternalDevServerWebSocketHandshakeInterceptor.class);
        WebSocketHandlerRegistry registry = mock(WebSocketHandlerRegistry.class);
        WebSocketHandlerRegistration publicRegistration = mock(WebSocketHandlerRegistration.class);
        WebSocketHandlerRegistration internalRegistration = mock(WebSocketHandlerRegistration.class);
        when(registry.addHandler(handler, DevServerPreviewPaths.PUBLIC_PROXY_PREFIX + "**"))
                .thenReturn(publicRegistration);
        when(registry.addHandler(handler, DevServerPreviewPaths.INTERNAL_PROXY_PREFIX + "**"))
                .thenReturn(internalRegistration);
        when(publicRegistration.addInterceptors(publicInterceptor)).thenReturn(publicRegistration);
        when(internalRegistration.addInterceptors(internalInterceptor)).thenReturn(internalRegistration);

        new DevServerWebSocketConfiguration(handler, publicInterceptor, internalInterceptor)
                .registerWebSocketHandlers(registry);

        verify(publicRegistration).setAllowedOrigins("null");
        verify(internalRegistration, never()).setAllowedOrigins(any(String[].class));
    }
}
