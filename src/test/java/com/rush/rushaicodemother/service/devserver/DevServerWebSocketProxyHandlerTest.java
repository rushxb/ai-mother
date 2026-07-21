package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.config.DevServerWebSocketProxyProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.util.unit.DataSize;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DevServerWebSocketProxyHandlerTest {

    @Test
    void shouldQueueUntilConnectedThenBridgeBothDirectionsWithOnlyTrustedHeaders() throws Exception {
        AtomicReference<URI> connectedTarget = new AtomicReference<>();
        AtomicReference<WebSocketHttpHeaders> connectedHeaders = new AtomicReference<>();
        AtomicReference<WebSocketHandler> outboundHandler = new AtomicReference<>();
        CompletableFuture<WebSocketSession> connectFuture = new CompletableFuture<>();
        DevServerWebSocketConnector connector = (target, headers, handler) -> {
            connectedTarget.set(target);
            connectedHeaders.set(headers);
            outboundHandler.set(handler);
            return connectFuture;
        };
        DevServerWebSocketProxyHandler handler = new DevServerWebSocketProxyHandler(
                connector,
                properties(DataSize.ofKilobytes(64), DataSize.ofKilobytes(32))
        );
        WebSocketSession inbound = session(
                "browser",
                "vite-hmr",
                Map.of(
                        DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE,
                        new DevServerWebSocketUpstream(
                                URI.create("ws://preview-node-b:8123/api/internal/dev-server/proxy/21/?token=abc"),
                                Map.of(DevServerInternalRequestSigner.SIGNATURE_HEADER, "trusted-signature")
                        )
                )
        );
        WebSocketSession outbound = session("vite", "vite-hmr", Map.of());

        handler.afterConnectionEstablished(inbound);
        handler.handleMessage(inbound, new TextMessage("queued"));
        outboundHandler.get().afterConnectionEstablished(outbound);
        connectFuture.complete(outbound);

        assertEquals(
                URI.create("ws://preview-node-b:8123/api/internal/dev-server/proxy/21/?token=abc"),
                connectedTarget.get()
        );
        assertEquals(List.of("vite-hmr"), connectedHeaders.get().getSecWebSocketProtocol());
        assertEquals("trusted-signature", connectedHeaders.get()
                .getFirst(DevServerInternalRequestSigner.SIGNATURE_HEADER));
        ArgumentCaptor<WebSocketMessage<?>> upstreamMessage = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(outbound).sendMessage(upstreamMessage.capture());
        assertEquals("queued", ((TextMessage) upstreamMessage.getValue()).getPayload());

        outboundHandler.get().handleMessage(outbound, new TextMessage("update"));
        ArgumentCaptor<WebSocketMessage<?>> browserMessage = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(inbound).sendMessage(browserMessage.capture());
        assertEquals("update", ((TextMessage) browserMessage.getValue()).getPayload());

        outboundHandler.get().afterConnectionClosed(outbound, CloseStatus.GOING_AWAY);
        verify(inbound).close(CloseStatus.GOING_AWAY);
    }

    @Test
    void shouldCloseOversizedMessagesBeforeTheyReachTheUpstream() throws Exception {
        CompletableFuture<WebSocketSession> connectFuture = new CompletableFuture<>();
        DevServerWebSocketConnector connector = (target, headers, handler) -> connectFuture;
        DevServerWebSocketProxyHandler handler = new DevServerWebSocketProxyHandler(
                connector,
                properties(DataSize.ofBytes(8), DataSize.ofBytes(4))
        );
        WebSocketSession inbound = session(
                "browser",
                "vite-hmr",
                Map.of(
                        DevServerWebSocketProxyHandler.UPSTREAM_ATTRIBUTE,
                        new DevServerWebSocketUpstream(
                                URI.create("ws://127.0.0.1:5180/api/app/dev-server/proxy/21/"),
                                Map.of()
                        )
                )
        );

        handler.afterConnectionEstablished(inbound);
        handler.handleMessage(inbound, new TextMessage("12345"));

        verify(inbound).close(CloseStatus.TOO_BIG_TO_PROCESS);
        assertTrue(connectFuture.isCancelled());
    }

    private DevServerWebSocketProxyProperties properties(DataSize buffer, DataSize message) {
        DevServerWebSocketProxyProperties properties = new DevServerWebSocketProxyProperties();
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setSendTimeLimit(Duration.ofSeconds(1));
        properties.setSendBufferSize(buffer);
        properties.setMaxMessageSize(message);
        return properties;
    }

    private WebSocketSession session(
            String id,
            String protocol,
            Map<String, Object> attributes
    ) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.getAcceptedProtocol()).thenReturn(protocol);
        when(session.getAttributes()).thenReturn(new HashMap<>(attributes));
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
