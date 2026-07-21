package com.rush.rushaicodemother.service.devserver;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

/** Outbound WebSocket connection port used by the Preview bridge. */
public interface DevServerWebSocketConnector {

    CompletableFuture<WebSocketSession> connect(
            URI targetUri,
            WebSocketHttpHeaders headers,
            WebSocketHandler handler
    );
}
